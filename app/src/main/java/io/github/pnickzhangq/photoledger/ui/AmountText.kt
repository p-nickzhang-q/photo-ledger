// 收据式金额排版（设计「账本墨绿」）：金额是账本的主角——
// ¥ 小号灰、整数大号加粗、小数缩一号灰；tnum 保证数字等宽对齐。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
fun AmountText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fractionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // 两位小数舍入清浮点尾差；整数不加千分位（金额普遍小，加符号反而吵）
    val rounded = kotlin.math.round(amount * 100) / 100
    val intPart = rounded.toLong().toString().removePrefix("-")
    // 小数位全零则省略（79.0 → "79"），非零保留有效位（15.1 → ".1"，含前导点）
    val cents = kotlin.math.round(kotlin.math.abs(rounded) * 100).toLong() % 100
    val decPart = if (cents == 0L) "" else "." + cents.toString().padStart(2, '0').trimEnd('0')
    val negative = rounded < 0
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontSize = style.fontSize * 0.62f,
                    color = fractionColor,
                    fontFeatureSettings = "tnum",
                ),
            ) {
                append(if (negative) "-¥" else "¥")
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) {
                append(intPart)
            }
            if (decPart.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        fontSize = style.fontSize * 0.72f,
                        color = fractionColor,
                        fontFeatureSettings = "tnum",
                    ),
                ) {
                    append(decPart)
                }
            }
        },
        style = style,
        color = color,
        modifier = modifier,
    )
}
