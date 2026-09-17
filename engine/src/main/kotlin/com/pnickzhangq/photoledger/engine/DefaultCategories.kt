package com.pnickzhangq.photoledger.engine

/**
 * 内置默认八类（票 08 类别体系种子）。
 * 桌面闸门（cli）与 App 首启种子同源此常量，保证 prompt 注入口径零分叉。
 */
val DEFAULT_CATEGORIES = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

/** 删除类别时其下 Entry 归入的兜底类别（不可删除）。 */
const val FALLBACK_CATEGORY = "其他"
