package com.dragonmeow.nyanlex.hub;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HubPlanDiscoveryTest {

    private static HubPlan downloadablePlan(String language, String modId) {
        return new HubPlan(language, List.of(
                new HubPlanItem(HubSource.mod(modId), modId, true, 1, 10, false, "sha")));
    }

    @Test
    void aSlowerOldLanguageCannotOverwriteTheNewResult() {
        HubPlanDiscovery discovery = new HubPlanDiscovery();
        List<Runnable> queued = new ArrayList<>();

        discovery.start("zh-TW", queued::add, language -> downloadablePlan(language, "old-mod"));
        discovery.start("ja-JP", queued::add, language -> downloadablePlan(language, "new-mod"));
        assertEquals(HubPlanDiscovery.State.DETECTING, discovery.state());

        queued.get(1).run();
        assertEquals(HubPlanDiscovery.State.FOUND, discovery.state());
        assertEquals("ja-jp", discovery.language());
        assertEquals("new-mod", discovery.plan().downloadable().get(0).label());

        queued.get(0).run();
        assertEquals(HubPlanDiscovery.State.FOUND, discovery.state());
        assertEquals("ja-jp", discovery.language());
        assertEquals("new-mod", discovery.plan().downloadable().get(0).label());
        assertNull(discovery.planFor("zh-TW"));
        assertEquals(discovery.plan(), discovery.planFor("ja-JP"));
    }

    @Test
    void emptyAndFailedDiscoveriesPublishTerminalStates() {
        HubPlanDiscovery discovery = new HubPlanDiscovery();
        discovery.start("en-US", Runnable::run, language -> HubPlan.empty(language));
        assertEquals(HubPlanDiscovery.State.NONE, discovery.state());

        discovery.start("en-US", Runnable::run, language -> {
            throw new java.io.IOException("offline");
        });
        assertEquals(HubPlanDiscovery.State.FAILED, discovery.state());
        assertNull(discovery.plan());
    }
}
