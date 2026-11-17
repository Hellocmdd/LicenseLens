package edu.course.licenselens.spi;

/**
 * 策略评估的决策结果。
 *
 * @param allowed  true = 依赖符合策略，可接受
 * @param warning  true = 有警告但不阻断（allowed=true && warning=true 时，提示但继续）
 * @param reason   决策理由，写入报告和终端摘要
 */
public record Decision(boolean allowed, boolean warning, String reason) {

    public static Decision allow(String reason) {
        return new Decision(true, false, reason);
    }

    public static Decision warn(String reason) {
        return new Decision(true, true, reason);
    }

    public static Decision block(String reason) {
        return new Decision(false, false, reason);
    }
}
