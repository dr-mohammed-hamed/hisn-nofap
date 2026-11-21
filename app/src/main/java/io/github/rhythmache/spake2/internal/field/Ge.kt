/*
 * Copyright (C) 2023 rhythmcache
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.rhythmache.spake2.internal.field

internal class GeP2(
    val X: Fe = Fe(),
    val Y: Fe = Fe(),
    val Z: Fe = Fe(),
)

internal class GeP3(
    val X: Fe = Fe(),
    val Y: Fe = Fe(),
    val Z: Fe = Fe(),
    val T: Fe = Fe(),
)

internal class GeP1P1(
    val X: FeLoose = FeLoose(),
    val Y: FeLoose = FeLoose(),
    val Z: FeLoose = FeLoose(),
    val T: FeLoose = FeLoose(),
)

internal class GeCached(
    val YplusX: FeLoose = FeLoose(),
    val YminusX: FeLoose = FeLoose(),
    val Z: FeLoose = FeLoose(),
    val T2d: FeLoose = FeLoose(),
)

internal class GePrecomp(
    val yplusx: FeLoose = FeLoose(),
    val yminusx: FeLoose = FeLoose(),
    val xy2d: FeLoose = FeLoose(),
)
