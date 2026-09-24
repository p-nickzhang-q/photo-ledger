// 票 31：版本比较纯逻辑单测（网络调用不测，真机走查）。
package io.github.pnickzhangq.photoledger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `同版本不算新`() {
        assertFalse(UpdateChecker.isNewer("0.4.9", "0.4.9"))
    }

    @Test
    fun `补丁位升级`() {
        assertTrue(UpdateChecker.isNewer("0.4.10", "0.4.9"))
        assertFalse(UpdateChecker.isNewer("0.4.9", "0.4.10"))
    }

    @Test
    fun `次版本与主版本升级`() {
        assertTrue(UpdateChecker.isNewer("0.5.0", "0.4.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `数字逐位比较非字符串比较`() {
        // 字符串比较会误判 "0.10.0" < "0.4.9"
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.4.9"))
    }

    @Test
    fun `缺位补零`() {
        assertTrue(UpdateChecker.isNewer("0.5", "0.4.9"))
        assertFalse(UpdateChecker.isNewer("0.4", "0.4.9"))
    }

    @Test
    fun `tag 尾缀容错`() {
        assertEquals(true, UpdateChecker.isNewer("0.5.0-beta.1", "0.4.9"))
    }
}
