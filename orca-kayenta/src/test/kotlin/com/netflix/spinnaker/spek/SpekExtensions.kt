/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.spek

import org.funktionale.partials.partially2
import org.jetbrains.spek.api.dsl.SpecBody

/**
 * Grammar for nesting inside [given].
 */
fun SpecBody.and(description: String, body: SpecBody.() -> Unit) {
  group("and $description", body = body)
}

fun <T1, T2> SpecBody.where(description: String, vararg rows: Row2<T1, T2>, block: SpecBody.(T1, T2) -> Unit) {
  rows.forEach {
    val arguments = arrayOf(it.value1, it.value2)
    val body = block.partially2(it.value1).partially2(it.value2)
    group(String.format("where $description", *arguments), body = body)
  }
}

fun <T1, T2, T3> SpecBody.where(description: String, vararg rows: Row3<T1, T2, T3>, block: SpecBody.(T1, T2, T3) -> Unit) {
  rows.forEach {
    val arguments = arrayOf(it.value1, it.value2, it.value3)
    val body = block.partially2(it.value1).partially2(it.value2).partially2(it.value3)
    group(String.format("where $description", *arguments), body = body)
  }
}

fun <T1, T2, T3, T4> SpecBody.where(description: String, vararg rows: Row4<T1, T2, T3, T4>, block: SpecBody.(T1, T2, T3, T4) -> Unit) {
  rows.forEach {
    val arguments = arrayOf(it.value1, it.value2, it.value3, it.value4)
    val body = block.partially2(it.value1).partially2(it.value2).partially2(it.value3).partially2(it.value4)
    group(String.format("where $description", *arguments), body = body)
  }
}

sealed class Row

data class Row1<out T1>(val value: T1) : Row()

fun <T1> row(element1: T1) =
  Row1(element1)

data class Row2<out T1, out T2>(val value1: T1, val value2: T2) : Row()

fun <T1, T2> row(value1: T1, value2: T2) =
  Row2(value1, value2)

data class Row3<out T1, out T2, out T3>(val value1: T1, val value2: T2, val value3: T3) : Row()

fun <T1, T2, T3> row(value1: T1, value2: T2, value3: T3) =
  Row3(value1, value2, value3)

data class Row4<out T1, out T2, out T3, out T4>(val value1: T1, val value2: T2, val value3: T3, val value4: T4) : Row()

fun <T1, T2, T3, T4> row(value1: T1, value2: T2, value3: T3, value4: T4) =
  Row4(value1, value2, value3, value4)

infix fun <T1, T2> T1.`|`(other: T2): Row2<T1, T2> = row(this, other)
infix fun <T1, T2, T3> Row2<T1, T2>.`|`(other: T3): Row3<T1, T2, T3> = row(value1, value2, other)
infix fun <T1, T2, T3, T4> Row3<T1, T2, T3>.`|`(other: T4): Row4<T1, T2, T3, T4> = row(value1, value2, value3, other)
