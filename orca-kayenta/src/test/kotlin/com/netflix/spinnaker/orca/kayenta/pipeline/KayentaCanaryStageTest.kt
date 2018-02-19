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

package com.netflix.spinnaker.orca.kayenta.pipeline

import com.netflix.spinnaker.orca.fixture.stage
import com.netflix.spinnaker.orca.kayenta.CanaryScope
import com.netflix.spinnaker.orca.pipeline.WaitStage
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.spek.and
import com.netflix.spinnaker.spek.row
import com.netflix.spinnaker.spek.where
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit.HOURS
import java.time.temporal.ChronoUnit.MINUTES

object KayentaCanaryStageTest : Spek({

  val waitStage = WaitStage()
  val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  val builder = KayentaCanaryStage(clock, waitStage)

  describe("planning a canary stage") {
    given("start/end times are specified") {
      where(
        "canary analysis should start after %s minutes",
        row(null, listOf("runCanary")),
        row("", listOf("runCanary")),
        row("0", listOf("runCanary")),
        row("30", listOf("wait", "runCanary"))
      ) { beginCanaryAnalysisAfterMins, expectedStageTypes ->
        val kayentaCanaryStage = stage {
          type = "kayentaCanary"
          name = "Run Kayenta Canary"
          context["canaryConfig"] = mapOf(
            "canaryConfigId" to "MySampleStackdriverCanaryConfig",
            "startTime" to clock.instant().toString(),
            "endTime" to clock.instant().plus(4, HOURS).toString(),
            "scopes" to listOf(mapOf(
              "controlScope" to "myapp-v010",
              "experimentScope" to "myapp-v021"
            )),
            "scoreThresholds" to mapOf("marginal" to 75, "pass" to 90),
            "beginCanaryAnalysisAfterMins" to beginCanaryAnalysisAfterMins
          )
        }

        val aroundStages = builder.aroundStages(kayentaCanaryStage)

        it("should not introduce wait stages") {
          assertThat(aroundStages).extracting("type").isEqualTo(expectedStageTypes)
        }
      }

      where(
        "canary should start after %s minutes with an interval of %s minutes and lookback of %s minutes",
        row(null, null, null, CanaryRanges(0 to 240)),
        row(null, "", "", CanaryRanges(0 to 240)),
        row(null, "0", "0", CanaryRanges(0 to 240)),
        row(null, "60", null, CanaryRanges(0 to 60, 0 to 120, 0 to 180, 0 to 240)),
        row("15", null, "", CanaryRanges(0 to 240)),
        row("15", "", "0", CanaryRanges(0 to 240)),
        row("15", "0", null, CanaryRanges(0 to 240)),
        row("15", "60", "", CanaryRanges(0 to 60, 0 to 120, 0 to 180, 0 to 240)),
        row(null, null, "120", CanaryRanges(120 to 240)),
        row(null, "", "60", CanaryRanges(180 to 240)),
        row(null, "0", "60", CanaryRanges(180 to 240)),
        row(null, "60", "60", CanaryRanges(0 to 60, 60 to 120, 120 to 180, 180 to 240)),
        row("15", null, "120", CanaryRanges(120 to 240)),
        row("15", "", "60", CanaryRanges(180 to 240)),
        row("15", "0", "60", CanaryRanges(180 to 240)),
        row("15", "60", "60", CanaryRanges(0 to 60, 60 to 120, 120 to 180, 180 to 240)),
        row(null, "300", null, CanaryRanges(0 to 240))
      ) { warmupMins, intervalMins, lookbackMins, canaryRanges ->
        val kayentaCanaryStage = stage {
          type = "kayentaCanary"
          name = "Run Kayenta Canary"
          context["canaryConfig"] = mapOf(
            "canaryConfigId" to "MySampleStackdriverCanaryConfig",
            "startTime" to clock.instant().toString(),
            "endTime" to clock.instant().plus(4, HOURS).toString(),
            "scopes" to listOf(mapOf(
              "controlScope" to "myapp-v010",
              "experimentScope" to "myapp-v021"
            )),
            "scoreThresholds" to mapOf("marginal" to 75, "pass" to 90),
            "beginCanaryAnalysisAfterMins" to warmupMins,
            "canaryAnalysisIntervalMins" to intervalMins,
            "lookbackMins" to lookbackMins
          )
        }

        val aroundStages = builder.aroundStages(kayentaCanaryStage)

        it("still handles canary intervals properly") {
          aroundStages
            .controlScopes()
            .apply {
              assertThat(map { clock.instant().until(it.start, MINUTES) }).isEqualTo(canaryRanges.startAtMin.map { it.toLong() })
              assertThat(map { clock.instant().until(it.end, MINUTES) }).isEqualTo(canaryRanges.endAtMin.map { it.toLong() })
              assertThat(map { it.step }).allMatch { it == Duration.ofMinutes(1) }
            }
        }

        if (warmupMins != null) {
          val expectedWarmupWait = warmupMins.toInt()
          it("inserts a warmup wait stage of $expectedWarmupWait minutes") {
            aroundStages
              .filter { it.name == "Warmup Wait" }
              .apply {
                assertThat(this).hasSize(1)
                assertThat(first().context["waitTime"])
                  .isEqualTo(expectedWarmupWait.minutesInSeconds.toLong())
              }
          }
        } else {
          it("does not insert a warmup wait stage") {
            assertThat(aroundStages).noneMatch { it.name == "Warmup Wait" }
          }
        }
      }
    }

    given("start and end times are not specified") {
      where(
        "canary analysis should begin after %s minutes",
        row(null, listOf("wait", "runCanary"), 0L),
        row("", listOf("wait", "runCanary"), 0L),
        row("0", listOf("wait", "runCanary"), 0L),
        row("30", listOf("wait", "wait", "runCanary"), 30L)
      ) { warmupMins, expectedStageTypes, expectedWarmupMins ->
        and("canary analysis should begin after $warmupMins minutes") {
          val kayentaCanaryStage = stage {
            type = "kayentaCanary"
            name = "Run Kayenta Canary"
            context["canaryConfig"] = mapOf(
              "canaryConfigId" to "MySampleStackdriverCanaryConfig",
              "scopes" to listOf(mapOf(
                "controlScope" to "myapp-v010",
                "experimentScope" to "myapp-v021"
              )),
              "scoreThresholds" to mapOf("marginal" to 75, "pass" to 90),
              "lifetimeHours" to "1",
              "beginCanaryAnalysisAfterMins" to warmupMins
            )
          }

          val builder = KayentaCanaryStage(clock, waitStage)
          val aroundStages = builder.aroundStages(kayentaCanaryStage)

          it("should start now") {
            assertThat(aroundStages).extracting("type").isEqualTo(expectedStageTypes)
            assertThat(aroundStages.controlScopes().map { it.start })
              .allMatch { it == clock.instant().plus(expectedWarmupMins, MINUTES) }
          }

          if (expectedWarmupMins > 0L) {
            it("inserts a warmup wait stage of $expectedWarmupMins minutes") {
              aroundStages
                .first()
                .apply {
                  assertThat(type).isEqualTo("wait")
                  assertThat(name).isEqualTo("Warmup Wait")
                  assertThat(context["waitTime"]).isEqualTo(expectedWarmupMins.minutesInSeconds)
                }
            }
          } else {
            it("does not insert a leading wait stage") {
              assertThat(aroundStages.filter { it.name == "Warmup Wait" }).isEmpty()
            }
          }
        }
      }

      where(
        "canary should start after %s minutes with an interval of %s minutes and lookback of %s minutes",
        row(null, null, null, CanaryRanges(0 to 48.hoursInMinutes)),
        row(null, "", "", CanaryRanges(0 to 48.hoursInMinutes)),
        row(null, "0", "0", CanaryRanges(0 to 48.hoursInMinutes)),
        row(null, "${8.hoursInMinutes}", null, CanaryRanges(0 to 8.hoursInMinutes, 0 to 16.hoursInMinutes, 0 to 24.hoursInMinutes, 0 to 32.hoursInMinutes, 0 to 40.hoursInMinutes, 0 to 48.hoursInMinutes)),
        row("45", null, "", CanaryRanges(45 to 45 + 48.hoursInMinutes)),
        row("45", "", "0", CanaryRanges(45 to 45 + 48.hoursInMinutes)),
        row("45", "0", null, CanaryRanges(45 to 45 + 48.hoursInMinutes)),
        row("45", "${8.hoursInMinutes}", "", CanaryRanges(45 to 45 + 8.hoursInMinutes, 45 to 45 + 16.hoursInMinutes, 45 to (45 + 24.hoursInMinutes), 45 to 45 + 32.hoursInMinutes, 45 to 45 + 40.hoursInMinutes, 45 to 45 + 48.hoursInMinutes)),
        row(null, null, "60", CanaryRanges(47.hoursInMinutes to 48.hoursInMinutes)),
        row(null, "", "60", CanaryRanges(47.hoursInMinutes to 48.hoursInMinutes)),
        row(null, "0", "60", CanaryRanges(47.hoursInMinutes to 48.hoursInMinutes)),
        row(null, "${8.hoursInMinutes}", "60", CanaryRanges(7.hoursInMinutes to 8.hoursInMinutes, 15.hoursInMinutes to 16.hoursInMinutes, 23.hoursInMinutes to 24.hoursInMinutes, 31.hoursInMinutes to 32.hoursInMinutes, 39.hoursInMinutes to 40.hoursInMinutes, 47.hoursInMinutes to 48.hoursInMinutes)),
        row("45", null, "60", CanaryRanges(45 + 47.hoursInMinutes to 45 + 48.hoursInMinutes)),
        row("45", "", "60", CanaryRanges(45 + 47.hoursInMinutes to 45 + 48.hoursInMinutes)),
        row("45", "0", "60", CanaryRanges(45 + 47.hoursInMinutes to 45 + 48.hoursInMinutes)),
        row("45", "${8.hoursInMinutes}", "60", CanaryRanges(45 + 7.hoursInMinutes to 45 + 8.hoursInMinutes, 45 + 15.hoursInMinutes to 45 + 16.hoursInMinutes, 45 + 23.hoursInMinutes to 45 + 24.hoursInMinutes, 45 + 31.hoursInMinutes to 45 + 32.hoursInMinutes, 45 + 39.hoursInMinutes to 45 + 40.hoursInMinutes, 45 + 47.hoursInMinutes to 45 + 48.hoursInMinutes))
      ) { warmupMins, intervalMins, lookbackMins, canaryRanges ->

        val canaryDuration = Duration.ofHours(48)

        val kayentaCanaryStage = stage {
          type = "kayentaCanary"
          name = "Run Kayenta Canary"
          context["canaryConfig"] = mapOf(
            "canaryConfigId" to "MySampleStackdriverCanaryConfig",
            "scopes" to listOf(mapOf(
              "controlScope" to "myapp-v010",
              "experimentScope" to "myapp-v021"
            )),
            "scoreThresholds" to mapOf("marginal" to 75, "pass" to 90),
            "beginCanaryAnalysisAfterMins" to warmupMins,
            "canaryAnalysisIntervalMins" to intervalMins,
            "lookbackMins" to lookbackMins,
            "lifetimeHours" to canaryDuration.toHours().toString()
          )
        }

        val aroundStages = builder.aroundStages(kayentaCanaryStage)

        if (warmupMins != null) {
          val expectedWarmupWait = warmupMins.toInt()
          it("inserts a warmup wait stage of $expectedWarmupWait minutes") {
            aroundStages
              .first()
              .apply {
                assertThat(type).isEqualTo("wait")
                assertThat(name).isEqualTo("Warmup Wait")
                assertThat(context["waitTime"]).isEqualTo(expectedWarmupWait.minutesInSeconds.toLong())
              }
          }
        } else {
          it("does not insert a leading wait stage") {
            assertThat(aroundStages.filter { it.name == "Warmup Wait" }).isEmpty()
          }
        }

        it("generates the correct ranges for each canary analysis phase") {
          aroundStages.controlScopes()
            .apply {
              assertThat(map { clock.instant().until(it.start, MINUTES) }).isEqualTo(canaryRanges.startAtMin.map { it.toLong() })
              assertThat(map { clock.instant().until(it.end, MINUTES) }).isEqualTo(canaryRanges.endAtMin.map { it.toLong() })
              assertThat(map { it.step }).allMatch { it == Duration.ofMinutes(1) }
            }
        }

        if (intervalMins != null && intervalMins.isNotEmpty() && intervalMins != "0") {
          val expectedIntervalWait = intervalMins.toInt()
          it("interleaves wait stages of $expectedIntervalWait minutes") {
            aroundStages
              .filter { it.name.matches(Regex("Interval Wait #\\d+")) }
              .waitTimes()
              .apply {
                assertThat(this).hasSize(canaryRanges.startAtMin.size)
                assertThat(this).allMatch { it == expectedIntervalWait.minutesInSeconds.toLong() }
              }
          }
        } else {
          val expectedIntervalWait = canaryDuration
          it("adds a single wait stage of the entire canary duration (${expectedIntervalWait.toHours()} hours)") {
            aroundStages
              .filter { it.name.matches(Regex("Interval Wait #\\d+")) }
              .waitTimes()
              .apply {
                assertThat(this).hasSize(1)
                assertThat(first()).isEqualTo(expectedIntervalWait.seconds)
              }
          }
        }
      }
    }

    given("additional canary attributes") {
      val attributes = mapOf("type" to "node")

      val kayentaCanaryStage = stage {
        type = "kayentaCanary"
        name = "Run Kayenta Canary"
        context["canaryConfig"] = mapOf(
          "metricsAccountName" to "atlas-acct-1",
          "canaryConfigId" to "MySampleAtlasCanaryConfig",
          "step" to "PT1M",
          "scopes" to listOf(mapOf(
            "controlScope" to "some.host.node",
            "experimentScope" to "some.other.host.node",
            "extendedScopeParams" to attributes
          )),
          "scoreThresholds" to mapOf("marginal" to 75, "pass" to 90),
          "canaryAnalysisIntervalMins" to 6.hoursInMinutes,
          "lifetimeHours" to "12"
        )
      }

      val aroundStages = builder.aroundStages(kayentaCanaryStage)

      it("propagates the additional attributes") {
        assertThat(aroundStages.controlScopes())
          .extracting("extendedScopeParams")
          .allMatch { it == attributes }
      }
    }
  }
})

data class CanaryRanges(
  val startAtMin: List<Int>,
  val endAtMin: List<Int>
) {
  constructor(vararg range: Pair<Int, Int>) :
    this(range.map { it.first }, range.map { it.second })
}

/**
 * Get [scopeName] control scope from all [RunCanaryPipelineStage]s.
 */
fun List<Stage>.controlScopes(scopeName: String = "default"): List<CanaryScope> =
  filter { it.type == RunCanaryPipelineStage.STAGE_TYPE }
    .map { it.mapTo("/scopes/$scopeName/controlScope", CanaryScope::class.java) }

/**
 * Get wait time from any [WaitStage]s.
 */
fun List<Stage>.waitTimes(): List<Long> =
  filter { it.type == "wait" }
    .map { it.mapTo("/waitTime", Long::class.java) }

val Int.hoursInMinutes: Int
  get() = Duration.ofHours(this.toLong()).toMinutes().toInt()

val Int.hoursInSeconds: Int
  get() = Duration.ofHours(this.toLong()).seconds.toInt()

val Int.minutesInSeconds: Int
  get() = Duration.ofMinutes(this.toLong()).seconds.toInt()

val Long.minutesInSeconds: Long
  get() = Duration.ofMinutes(this).seconds

