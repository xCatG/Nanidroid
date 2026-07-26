package com.cattailsw.nanidroid.runtime

/**
 * Pure interpreter for Sakura Script commands that affect ghost presentation.
 *
 * This is intentionally not the production runtime yet: user input, choices,
 * Shiori callbacks, timing, and lifecycle effects remain in the legacy runner
 * until they receive equivalent Kotlin boundaries. It does, however, preserve
 * the observable frame ordering for the presentation command subset.
 */
object SakuraScriptPresentationInterpreter {
    @JvmStatic
    fun interpret(script: String): List<GhostPresentationState> {
        var state = SakuraScriptPresentationReducer.resetForNextScript(
            SakuraScriptPresentationReducer.initial(),
        )
        var index = 0
        var wholeLine = false
        val frames = mutableListOf<GhostPresentationState>()

        fun emit() {
            frames += SakuraScriptPresentationReducer.snapshot(state)
            state = SakuraScriptPresentationReducer.consumeAnimations(state)
        }

        while (index < script.length) {
            var emitted = false
            while (index < script.length) {
                val character = script[index++]
                if (character != '\\') {
                    state = SakuraScriptPresentationReducer.append(state, character)
                    if (!wholeLine) {
                        emit()
                        emitted = true
                        break
                    }
                    continue
                }

                if (index >= script.length) break
                when (val command = script[index++]) {
                    '0', 'h' -> state = SakuraScriptPresentationReducer.selectSpeaker(
                        state,
                        GhostSpeaker.SAKURA,
                    )

                    '1', 'u' -> state = SakuraScriptPresentationReducer.selectSpeaker(
                        state,
                        GhostSpeaker.KERO,
                    )

                    's' -> {
                        parseId(script, index)?.let { (surfaceId, nextIndex) ->
                            state = SakuraScriptPresentationReducer.changeSurface(state, surfaceId)
                            index = nextIndex
                        }
                        emit()
                        emitted = true
                        break
                    }

                    'i' -> {
                        parseBracketId(script, index)?.let { (animationId, nextIndex) ->
                            state = SakuraScriptPresentationReducer.queueAnimation(state, animationId)
                            index = nextIndex
                        }
                        emit()
                        emitted = true
                        break
                    }

                    'b' -> {
                        parseId(script, index)?.let { (balloonId, nextIndex) ->
                            state = SakuraScriptPresentationReducer.changeBalloon(state, balloonId)
                            index = nextIndex
                        }
                        emit()
                        emitted = true
                        break
                    }

                    'n' -> {
                        state = SakuraScriptPresentationReducer.append(state, '\n')
                        index = skipOptionalBracket(script, index)
                        emit()
                        emitted = true
                        break
                    }

                    'c' -> state = SakuraScriptPresentationReducer.clearActiveText(state)
                    'e' -> {
                        index = script.length
                        emit()
                        emitted = true
                        break
                    }

                    '_' -> if (index < script.length) {
                        when (script[index++]) {
                            's' -> state = SakuraScriptPresentationReducer.toggleSynchronization(state)
                            'q' -> wholeLine = !wholeLine
                        }
                    }

                    else -> Unit // Non-presentation effects remain on the legacy runtime path.
                }
            }
            if (!emitted && index >= script.length) break
        }

        // Legacy stop resets the transient presentation then dispatches one final frame.
        state = SakuraScriptPresentationReducer.resetForNextScript(state)
        frames += SakuraScriptPresentationReducer.snapshot(state)
        return frames
    }

    private fun parseId(script: String, index: Int): Pair<String, Int>? {
        parseBracketId(script, index)?.let { return it }
        if (index < script.length && script[index].isDigit()) {
            return script[index].toString() to index + 1
        }
        return null
    }

    private fun parseBracketId(script: String, index: Int): Pair<String, Int>? {
        if (index >= script.length || script[index] != '[') return null
        val closing = script.indexOf(']', index + 1)
        if (closing < 0) return null
        return script.substring(index + 1, closing) to closing + 1
    }

    private fun skipOptionalBracket(script: String, index: Int): Int =
        parseBracketId(script, index)?.second ?: index
}
