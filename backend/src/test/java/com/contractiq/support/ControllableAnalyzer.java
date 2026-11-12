package com.contractiq.support;

import com.contractiq.contract.ai.AnalysisResult;
import com.contractiq.contract.ai.ContractAnalyzer;
import com.contractiq.contract.ai.MockContractAnalyzer;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A test double for {@link ContractAnalyzer}. Behaviour is scripted per "marker": a unique word a
 * test puts in its contract text. A call whose text contains a registered marker follows that
 * script (one step per call); any other call falls back to the rule-based mock. Keying by marker
 * keeps tests independent even if a background job from an earlier test is still running.
 */
public class ControllableAnalyzer implements ContractAnalyzer {

    /** One recorded call: the exact text received and, for a corrective retry, the problems listed. */
    public record Call(String text, List<String> problems) {
    }

    private final MockContractAnalyzer mock = new MockContractAnalyzer();
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    /** Returns the script for a marker, creating it on first use. */
    public Script script(String marker) {
        return scripts.computeIfAbsent(marker, key -> new Script());
    }

    @Override
    public AnalysisResult analyze(String contractText) {
        return handle(contractText, null);
    }

    @Override
    public AnalysisResult analyzeWithCorrection(String contractText, List<String> problems) {
        return handle(contractText, problems);
    }

    private AnalysisResult handle(String text, List<String> problems) {
        Script script = scripts.entrySet().stream()
                .filter(entry -> text.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (script == null) {
            return mock.analyze(text);
        }
        script.calls.add(new Call(text, problems));
        try {
            Function<String, AnalysisResult> step = script.steps.poll();
            return step == null ? mock.analyze(text) : step.apply(text);
        } finally {
            script.finished.incrementAndGet();
        }
    }

    /** What happens on each call for one marker. Steps run in order; after the last, the mock is used. */
    public static class Script {
        private final Queue<Function<String, AnalysisResult>> steps = new ConcurrentLinkedQueue<>();
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger finished = new AtomicInteger();

        public Script then(Function<String, AnalysisResult> step) {
            steps.add(step);
            return this;
        }

        public Script thenReturn(AnalysisResult result) {
            return then(text -> result);
        }

        public Script thenThrow(RuntimeException exception) {
            return then(text -> {
                throw exception;
            });
        }

        /** Waits (up to 30 seconds) for the latch, then answers like the mock. */
        public Script thenBlockUntil(CountDownLatch latch) {
            return then(text -> {
                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new MockContractAnalyzer().analyze(text);
            });
        }

        public List<Call> calls() {
            return calls;
        }

        /** How many calls have returned or thrown (as opposed to merely started). */
        public int finishedCalls() {
            return finished.get();
        }
    }
}
