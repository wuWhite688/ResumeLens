package com.arthur.jdragresume.support;

import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DockerSkipNoticeListener implements TestExecutionListener {
    private static final String CASCADE_TEST_CLASS =
            "com.arthur.jdragresume.integration.ResumeDeleteCascadeMySqlTests";
    private static final String DOCKER_UNAVAILABLE = "Docker is not available";
    private final AtomicBoolean cascadeTestsSkipped = new AtomicBoolean();

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (reason == null || !reason.contains(DOCKER_UNAVAILABLE)) {
            return;
        }
        testIdentifier.getSource()
                .map(DockerSkipNoticeListener::sourceClassName)
                .filter(CASCADE_TEST_CLASS::equals)
                .ifPresent(ignored -> cascadeTestsSkipped.set(true));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (cascadeTestsSkipped.get()) {
            System.err.println();
            System.err.println(
                    "[WARNING] ResumeDeleteCascadeMySqlTests SKIPPED: Docker unavailable; "
                            + "2 MySQL cascade tests did not run. Run ./mvnw test inside WSL "
                            + "after starting dockerd (see README)."
            );
        }
    }

    private static String sourceClassName(TestSource source) {
        if (source instanceof ClassSource classSource) {
            return classSource.getClassName();
        }
        if (source instanceof MethodSource methodSource) {
            return methodSource.getClassName();
        }
        return "";
    }
}
