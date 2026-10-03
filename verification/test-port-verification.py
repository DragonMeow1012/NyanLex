"""Regression tests for release coverage and the class-file Mixin inspection."""
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

from release_matrix import targets

spec = importlib.util.spec_from_file_location("port_mixins", Path(__file__).with_name("check-port-mixins.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
feature_spec = importlib.util.spec_from_file_location("translation_features", Path(__file__).with_name("verify-translation-features.py"))
features = importlib.util.module_from_spec(feature_spec)
feature_spec.loader.exec_module(features)


class MatrixTests(unittest.TestCase):
    def test_complete_stable_release_coverage(self):
        rows = targets()
        self.assertEqual(62, len(rows))
        self.assertEqual(44, sum(row.expanded for row in rows))
        self.assertEqual(37, sum(row.loader == "fabric" for row in rows))
        self.assertEqual(23, sum(row.loader == "neoforge" for row in rows))
        self.assertEqual(len(rows), len({row.relative for row in rows}))
        self.assertNotIn("neoforge/1.17", {row.key for row in rows})

    def test_descriptor_arguments_include_arrays_and_doubles(self):
        self.assertEqual(["[I", "[[Ljava/lang/String;", "D", "Z"],
                         checker.arguments("([I[[Ljava/lang/String;DZ)V"))

    def test_selector_uses_exact_descriptor(self):
        member = dict(name="work", descriptor="(ID)V")
        self.assertTrue(checker.matches("work(ID)V", member))
        self.assertFalse(checker.matches("work(I)V", member))
        self.assertTrue(checker.matches("work*", member))

    def test_rejects_invalid_magic(self):
        with self.assertRaisesRegex(ValueError, "Not a JVM class"):
            checker.ClassReader(b"not-java").parse()


class ClassReaderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="nyanlex-class-check-")
        root = Path(cls.directory.name)
        source = root / "Fixture.java"
        source.write_text('''
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @interface Point { String target(); }
@Retention(RetentionPolicy.RUNTIME) @interface Marker {
    Class<?>[] value(); String[] method(); Point at(); int require();
}
@Marker(value={Fixture.class}, method={"work(I)Ljava/lang/String;"},
        at=@Point(target="Ljava/lang/String;trim()Ljava/lang/String;"), require=1)
class Fixture implements Runnable {
    public void run() {}
    static int counter;
    String work(int n) {
        counter++;
        switch (n) { case 0: n=10; break; case 1: n=20; break;
                     case 2: n=30; break; default: n=40; }
        return String.valueOf(n).trim();
    }
    String sparse(int n) {
        switch (n) { case -100000: return "a".trim();
                     case 200000: return "b".trim(); default: return "c".trim(); }
    }
}
''', encoding="utf-8")
        jdk = Path(os.environ.get("NYANLEX_JDK21", "C:/Program Files/Java/jdk-21"))
        subprocess.run([str(jdk / "bin/javac.exe"), "--release", "8", str(source)], check=True, capture_output=True)
        cls.parsed = checker.ClassReader((root / "Fixture.class").read_bytes()).parse()

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_class_and_nested_annotation(self):
        self.assertEqual("Fixture", self.parsed["name"])
        self.assertEqual("java/lang/Object", self.parsed["parent"])
        self.assertEqual(["java/lang/Runnable"], self.parsed["interfaces"])
        marker = self.parsed["annotations"]["LMarker;"]
        self.assertEqual(["LFixture;"], marker["value"])
        self.assertEqual(["work(I)Ljava/lang/String;"], marker["method"])
        self.assertEqual(1, marker["require"])
        self.assertEqual("Ljava/lang/String;trim()Ljava/lang/String;", marker["at"][1]["target"])

    def test_tableswitch_does_not_hide_invocations(self):
        method = next(row for row in self.parsed["methods"] if row["name"] == "work")
        self.assertIn("LFixture;counter:I", method["calls"])
        self.assertIn("Ljava/lang/String;trim()Ljava/lang/String;", method["calls"])
        self.assertIn("Ljava/lang/String;valueOf(I)Ljava/lang/String;", method["calls"])

    def test_lookupswitch_does_not_hide_invocations(self):
        method = next(row for row in self.parsed["methods"] if row["name"] == "sparse")
        self.assertEqual(3, method["calls"].count("Ljava/lang/String;trim()Ljava/lang/String;"))


class ComposerBoundaryTests(unittest.TestCase):
    def check_archive(self, anonymous):
        buffer = io.BytesIO()
        name = "com/dragonmeow/nyanlex/fabric/mixin/ChatComposerMixin.class"
        with ZipFile(buffer, "w") as jar:
            jar.writestr(name, b"\xca\xfe\xba\xbe\x00\x00\x00\x34" +
                         b"com/dragonmeow/nyanlex/translate/ChatComposerPanel$Host")
            if anonymous:
                jar.writestr(name[:-6] + "$1.class", b"fixture")
        with ZipFile(buffer) as jar:
            features.assert_composer_boundary(jar, name, "fixture", True)

    def test_direct_host_has_no_generated_mixin_class(self):
        self.check_archive(False)

    def test_old_anonymous_adapter_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "unsafe composer Mixin inner classes"):
            self.check_archive(True)


class BytecodeLevelTests(unittest.TestCase):
    def test_newer_helper_class_cannot_hide_behind_valid_metadata(self):
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as jar:
            jar.writestr("Main.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x34")
            jar.writestr("Helper.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x45")
        with ZipFile(buffer) as jar:
            with self.assertRaisesRegex(AssertionError, "Java bytecode exceeds declared target"):
                features.assert_java_level(jar, "java8-fixture", 8)
            features.assert_java_level(jar, "java25-fixture", 25)


if __name__ == "__main__":
    unittest.main()
