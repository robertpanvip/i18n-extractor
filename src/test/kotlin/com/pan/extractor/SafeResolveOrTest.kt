package com.pan.extractor

import com.intellij.openapi.progress.ProcessCanceledException
import com.pan.extractor.analyzer.safeResolveOr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * 回归：IntelliJ 平台内部引用解析抛出的异常必须被熔断，不能冒泡到 IDE UI 线程。
 *
 * 实测崩溃链：inlay toggle 更新（UI 线程 invokeLater）→ isTranslationCall → analyze →
 * resolvedOf → ReadAction.compute { ref.resolve() } →
 * JSDefaultFileReferenceContext.actualDirectory 的 Objects.requireNonNull → NPE。
 * NPE 未被拦截 → 冲到 IdeEventQueue → IDE 弹错误通知。
 *
 * 修复口径：safeResolveOr 把平台 NPE/AssertionError/IllegalStateException 等一律降级为
 * fallback（上层走文件级结构证明回退）；仅 ProcessCanceledException（用户取消/平台超时）
 * 与 VirtualMachineError/ThreadDeath（致命错误）保持传播。
 */
class SafeResolveOrTest {

    @Test
    fun `平台 NPE 降级为 fallback 而不抛出`() {
        val result = safeResolveOr<String?>(null) {
            throw NullPointerException("actualDirectory requireNonNull")
        }
        assertNull(result)
    }

    @Test
    fun `平台 AssertionError 降级为 fallback`() {
        val result = safeResolveOr("fallback") { throw AssertionError("PSI inconsistent") }
        assertEquals("fallback", result)
    }

    @Test
    fun `平台 IllegalStateException 降级为 fallback`() {
        val result = safeResolveOr("fallback") { throw IllegalStateException("no ProgressIndicator") }
        assertEquals("fallback", result)
    }

    @Test
    fun `正常返回值原样透传`() {
        val sentinel = Any()
        val result = safeResolveOr<Any?>(null) { sentinel }
        assertSame(sentinel, result)
    }

    @Test
    fun `ProcessCanceledException 必须传播`() {
        val pce = ProcessCanceledException()
        try {
            safeResolveOr<String?>(null) { throw pce }
            fail("PCE 应当传播，不得被当作普通解析失败吞掉")
        } catch (e: ProcessCanceledException) {
            assertSame(pce, e)
        }
    }

    @Test
    fun `致命 VM 错误必须传播`() {
        try {
            safeResolveOr<String?>(null) { throw StackOverflowError("fatal") }
            fail("致命错误不得被吞")
        } catch (e: StackOverflowError) {
            // expected
        }
    }
}
