package com.dragonmeow.nyanslate.translate;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
public final class InlineCodexSimulation{
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);
        SessionTokenUsage usage=new SessionTokenUsage();
        long elapsed;
        try(CodexAppServerClient client=new CodexAppServerClient(root.resolve("home"),root.resolve("workspace"))){
            client.setTokenUsage(usage);
            check(client.isInstalled(),"fake Codex executable probe failed: "+client.lastError());
            check(client.readAccount(false).signedIn(),"account/read did not report ChatGPT sign-in");
            CodexAppServerClient.LoginStart login=client.startLogin();
            check(client.awaitLogin(login.loginId(),Duration.ofSeconds(3)),"ChatGPT login event was not completed");
            List<CodexAppServerClient.ModelOption> models=client.listModels();
            check(models.size()==2,"paginated model/list did not return two models");
            CodexAppServerClient.ModelOption terra=models.get(0);
            check("gpt-5.6-terra".equals(terra.model()),"terra was not first/default model");
            check(terra.reasoningEfforts().contains("medium"),"terra medium effort missing");
            check(terra.serviceTiers().contains("priority"),"terra priority tier missing");
            long started=System.nanoTime();
            String translated=client.complete("gpt-5.6-terra","medium","Translate only.","Bloom Boat with Chest");
            elapsed=(System.nanoTime()-started)/1_000_000L;
            check("帶有箱子的花船".equals(translated),"translation payload mismatch: "+translated);
            check(elapsed<3000L,"completion waited too long: "+elapsed+" ms");
            Thread.sleep(250L);
            client.logout();
            check(!client.isSignedInCached(),"logout did not clear cached account");
        }
        SessionTokenUsage.Snapshot s=usage.snapshot();
        check(s.inputTokens()==10&&s.cachedInputTokens()==3&&s.outputTokens()==4&&s.reasoningOutputTokens()==1&&s.totalTokens()==14&&s.requests()==1,"token HUD counters mismatch: "+s);
        System.out.println("INLINE_CODEX_OK modern "+elapsed+"ms");
    }
}
