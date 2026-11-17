package edu.course.licenselens.core;

import edu.course.licenselens.spi.Policy;

import java.util.List;

/**
 * 策略引擎，内置两种策略实现。A 负责。
 * <p>
 * 注册方式：ScanEngine 构造时传入。
 * CLI 通过 {@code --policy} 参数 {@link #select(String)} 选择。
 */
public class PolicyEngine {

    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = policies;
    }

    /** 按 id 选择策略；未找到抛出 IllegalArgumentException。 */
    public Policy select(String id) {
        return policies.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知策略: " + id));
    }
}
