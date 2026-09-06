package org.sift.operator.validation;

import java.util.Objects;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** Test-only initializer, loaded alongside the executable artifact, never packaged into the agent. */
public final class ConfigProbe implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.addApplicationListener(event -> {
            if (event instanceof ApplicationStartedEvent) {
                try {
                    verify(context);
                    System.out.println("PACKAGED_CONFIG_PROBE_OK");
                    System.exit(0);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Cannot inspect packaged review binding", exception);
                }
            }
        });
    }

    private static void verify(ConfigurableApplicationContext context) throws ReflectiveOperationException {
        Environment environment = context.getEnvironment();
        Class<?> type = Class.forName("org.sift.agents.review.ReviewProperties");
        Object review = context.getBean(type);
        equal("https://example.org/mounted.git", type.getMethod("getRepositoryUrl").invoke(review));
        equal("mounted-branch", type.getMethod("getBranch").invoke(review));
        equal("mounted-base", type.getMethod("getBaseBranch").invoke(review));
        equal("73", type.getMethod("getPullRequest").invoke(review));
        equal("none", environment.getProperty("spring.main.web-application-type"));
        equal("5s", environment.getProperty("spring.rabbitmq.connection-timeout"));
        equal("true", environment.getProperty("spring.rabbitmq.template.retry.enabled"));
        equal("1s", environment.getProperty("spring.rabbitmq.template.retry.initial-interval"));
        equal("2.0", environment.getProperty("spring.rabbitmq.template.retry.multiplier"));
        equal("3", environment.getProperty("spring.rabbitmq.template.retry.max-retries"));
        equal("mounted-rabbit", environment.getProperty("spring.rabbitmq.host"));
        equal("http://model/wire/probe-token/codex/openai/v1", environment.getProperty("spring.ai.openai.base-url"));
        equal("mounted-model", environment.getProperty("spring.ai.openai.chat.options.model"));
        equal("a".repeat(40), environment.getProperty("sift.review.commit-sha"));
        equal("probe-uid:7", environment.getProperty("sift.review.execution-id"));
        equal(0, environment.getActiveProfiles().length);
        Class.forName("org.sift.agents.shared.advisors.ToolAllowlistAdvisor");
        // SHA/identity are currently environment-only: this test does not pretend the agent binds them.
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Packaged configuration assertion failed: expected " + expected + ", got " + actual);
        }
    }
}