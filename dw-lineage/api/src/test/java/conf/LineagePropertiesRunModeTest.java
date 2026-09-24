package conf;

import com.dwai.lineage.conf.LineageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code runMode()} 归一化的取值表。
 *
 * <p>不起 Spring 上下文是有意的：这里要穷举的是<b>函数本身</b>的每一格，
 * 而起一次上下文只能验一个配置值（每个不同的属性值都是一份新的上下文缓存）。
 * 表放在这里，装配是否真的接上了门禁由 {@code controller.RunModeSmokeTest} 的
 * {@code BlankRunMode} 负责 —— 功能函数对、装配错，照样是裸奔，两者缺一不可。
 *
 * <p><b>这张表的立场：只有显式写 {@code standalone} 才是 standalone。</b>
 * 它是唯一不做认证的模式，所以必须是「说出来才生效」，不能被兜底兜进去。
 * 空值、乱码、大小写之外的值，一律落在 {@code standard}。
 */
class LineagePropertiesRunModeTest {

    private static LineageProperties withRunMode(String value) {
        return with(value, "");
    }

    private static LineageProperties with(String runMode, String deployMode) {
        LineageProperties p = new LineageProperties();
        p.setRunMode(runMode);
        p.setDeployMode(deployMode);
        return p;
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @CsvSource({
            // 显式三值，照收
            "standalone, standalone",
            "standard,   standard",
            "multi,      multi",
            // 大小写与空白：归一化，不是拒绝
            "STANDALONE, standalone",
            "  standard, standard",
            "Multi,      multi",
            // 说不明白的，一律朝「要认证」倒
            "'',         standard",
            "'   ',      standard",
            "bogus,      standard",
            "standalone2, standard",
            "saas,       standard",
    })
    void runModeNormalisation(String configured, String expected) {
        assertEquals(expected, withRunMode(configured).runMode());
    }

    /**
     * 回落链：{@code LINEAGE_RUN_MODE} 没给（或被设成空串）时才看 {@code DW_AI_MODE}。
     *
     * <p>加这一级的起因是个真实故障：三个模块共用一组部署变量，运维按 README 统一设
     * {@code DW_AI_MODE=standalone}，dw-org / dw-model 都正常，只有数据地图静默跑成
     * standard —— 表现为「独立部署却要登录」，日志里没有任何异常。
     *
     * <p><b>注意 {@code run-mode} 给了但认不出时不回落 deploy-mode</b>（最后一行）：
     * 那一刻部署方已经明确指定了数据地图的模式，只是写错了，此时再去猜 {@code DW_AI_MODE}
     * 的意思，等于把「写错了」变成「静默换了个模式跑」—— 与空值兜底同一个道理，
     * 宁可落到最保守的 standard。dw-model 的同一处也是这个口径。
     */
    @ParameterizedTest(name = "[{index}] run-mode=\"{0}\" deploy-mode=\"{1}\" → {2}")
    @CsvSource({
            // run-mode 空：听 deploy-mode 的
            "'',        standalone, standalone",
            "'',        standard,   standard",
            "'',        multi,      multi",
            // run-mode 说了就听它的，deploy-mode 不参与
            "multi,     standalone, multi",
            "standalone, multi,     standalone",
            // 两级都不给 / 都给不明白 → 一律朝「要认证」倒
            "'',        '',         standard",
            "'',        bogus,      standard",
            "bogus,     standalone, standard",
    })
    void deployModeIsTheFallback(String runMode, String deployMode, String expected) {
        assertEquals(expected, with(runMode, deployMode).runMode());
    }

    /**
     * 什么都不配时是 standard —— 兜底链的终点。
     *
     * <p>字段默认值本身是<b>空串</b>（不是 standard）：它与 yml 的
     * {@code ${LINEAGE_RUN_MODE:}} 一起构成回落链的第一级，一旦写成非空值，
     * {@code DW_AI_MODE} 那条回落就永远走不到（dw-model 失效过一次）。
     * 所以「默认 standard」这件事由 {@link LineageProperties#runMode()} 的兜底保证，
     * 而不是由字段默认值保证 —— 三处都朝「要认证」倒是结果，机制不同。
     */
    @Test
    void fieldDefaultIsStandard() {
        assertEquals("standard", new LineageProperties().runMode(),
                "什么都不配时不是 standard —— 兜底链的终点被人挪了");
    }

    /** 未设（null）与空串同路：都落到 standard，都不能变成无认证。 */
    @Test
    void unsetIsStandardNotStandalone() {
        assertTrue(withRunMode(null).isStandard(), "null 没有落到 standard");
        assertFalse(withRunMode(null).isStandalone(),
                "null 落到了 standalone —— 少写一个配置项就变成无认证");
    }
}
