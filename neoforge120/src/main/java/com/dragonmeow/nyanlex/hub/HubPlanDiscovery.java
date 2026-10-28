package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.cache.LanguageFileStore;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Owns one replaceable background translation-pack discovery. Starting a new language
 * invalidates the previous generation, so a slower old request can never publish stale
 * state or a plan for the wrong language.
 */
public final class HubPlanDiscovery {
    public enum State { IDLE, DETECTING, FOUND, NONE, FAILED }

    @FunctionalInterface
    public interface Planner {
        HubPlan plan(String normalizedLanguage) throws IOException;
    }

    private volatile State state = State.IDLE;
    private volatile String language;
    private volatile HubPlan plan;
    private long generation;

    public void start(String targetLanguage, Executor executor, Planner planner) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(planner, "planner");
        String normalized = LanguageFileStore.languageTag(targetLanguage);
        long request;
        synchronized (this) {
            request = ++generation;
            language = normalized;
            plan = null;
            state = State.DETECTING;
        }
        try {
            executor.execute(() -> discover(request, normalized, planner));
        } catch (RuntimeException rejected) {
            publishFailure(request);
        }
    }

    private void discover(long request, String normalized, Planner planner) {
        try {
            HubPlan found = planner.plan(normalized);
            if (found == null || !normalized.equals(found.language())) {
                publishFailure(request);
                return;
            }
            synchronized (this) {
                if (request != generation) return;
                plan = found;
                state = found.downloadable().isEmpty() ? State.NONE : State.FOUND;
            }
        } catch (IOException | RuntimeException failure) {
            publishFailure(request);
        }
    }

    private synchronized void publishFailure(long request) {
        if (request != generation) return;
        plan = null;
        state = State.FAILED;
    }

    public State state() {
        return state;
    }

    public String language() {
        return language;
    }

    public HubPlan plan() {
        return plan;
    }

    /** Returns the current plan only when it belongs to the requested language. */
    public HubPlan planFor(String targetLanguage) {
        String normalized = LanguageFileStore.languageTag(targetLanguage);
        HubPlan current = plan;
        return current != null && normalized.equals(language) && normalized.equals(current.language())
                ? current : null;
    }
}
