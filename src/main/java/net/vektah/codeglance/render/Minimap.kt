/*
 * Copyright © 2013, Adam Scarr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.vektah.codeglance.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType
import com.intellij.util.ui.UIUtil
import net.vektah.codeglance.config.Config

import java.awt.*
import java.awt.image.BufferedImage

/**
 * A rendered minimap of a document
 */
class Minimap(private val config: Config) {
    var img: BufferedImage? = null
    var height: Int = 0 //(px)
    private val logger = Logger.getInstance(javaClass)

    /**
     * Scans over the entire document once to work out the required dimensions then rebuilds the image if necessary.
     *
     * Because java chars are UTF-8 16 bit chars this function should be UTF safe in the 2 byte range, which is all
     * intellij seems to handle anyway....
     */
    fun updateDimensions(editor: Editor, linesCount: Int) {
        getPreferredHeight(editor)

        if (height < linesCount * config.pixelsPerLine) {
            height = linesCount * config.pixelsPerLine
        }

        // If the image is too small to represent the entire document now then regenerate it
        // TODO: Copy old image when incremental update is added.
        if (img == null || img!!.height < height || img!!.width < config.width) {
            if (img != null) img!!.flush()
            // Create an image that is a bit bigger then the one we need so we don't need to re-create it again soon.
            // Documents can get big, so rather then relative sizes lets just add a fixed amount on.
            img = UIUtil.createImage(config.width, height + 100 * config.pixelsPerLine, BufferedImage.TYPE_4BYTE_ABGR)
            logger.debug("Created new image")
        }
    }

    private fun getPreferredHeight(editor: Editor) {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            height = (editor as EditorImpl).preferredHeight / editor.lineHeight * config.pixelsPerLine
        }
    }

    private fun calculateLineOffsets(document: Document, folds: Folds): List<LineInfo> {
        val lineCount = document.lineCount - 1
        val lines: MutableList<LineInfo> = mutableListOf()
        var startOffset: Int? = null
        var endOffset: Int?

        for (lineNumber in 0..lineCount) {
            startOffset = startOffset ?: document.getLineStartOffset(lineNumber)
            endOffset = document.getLineEndOffset(lineNumber)
            if (folds.foldsMap[endOffset] != null) continue

            lines.add(LineInfo(lines.size, startOffset, endOffset))
            startOffset = null
        }

        return lines
    }

    /**
     * Works out the color a token should be rendered in.
     *
     * @param element       The element to get the color for
     * *
     * @param highlighter   the syntax highlighter for this document
     * *
     * @param colorScheme   the users color scheme
     * *
     * @return the RGB color to use for the given element
     */
    private fun getColorForElementType(element: IElementType,
                                       highlighter: SyntaxHighlighter,
                                       colorScheme: EditorColorsScheme): Int {
        var color = colorScheme.defaultForeground.rgb
        var tmp: Color?
        val attributes = highlighter.getTokenHighlights(element)
        for (attribute in attributes) {
            val attr = colorScheme.getAttributes(attribute)
            if (attr != null) {
                tmp = attr.foregroundColor
                if (tmp != null) color = tmp.rgb
            }
        }

        return color
    }

    /**
     * Internal worker function to update the minimap image
     *
     * @param editor        Current document editor
     * *
     * @param highlighter   The syntax highlighter to use for the language this document is in.
     * *
     * @param folds         Map of closed document folds
     */
    fun update(editor: Editor, highlighter: SyntaxHighlighter, folds: Folds) {
        try {
            logger.debug("Updating file image.")
            val document = editor.document
            val colorScheme = editor.colorsScheme

            val lines = calculateLineOffsets(document, folds)
            updateDimensions(editor, lines.size)
            freshImage()

            val text = document.text
            val lexer = highlighter.highlightingLexer

            for (line in lines) {
                lexer.start(text, line.begin, line.end)
                var tokenType: IElementType? = lexer.tokenType

                val y = line.number * config.pixelsPerLine
                var x = 0
                var placeholder: String? = null

                while (tokenType != null) {
                    val needDrawPlaceholder = placeholder == null
                    val color = getColorForElementType(tokenType, highlighter, colorScheme)

                    val start = lexer.tokenStart
                    placeholder = folds.foldsMap[start]

                    if (placeholder != null) {
                        if (needDrawPlaceholder) x += renderText(placeholder, x, y, color)
                    } else {
                        val end = lexer.tokenEnd
                        val word = text.subSequence(start, end)
                        x += renderText(word, x, y, color)
                    }

                    if (x >= config.width) break

                    lexer.advance()
                    tokenType = lexer.tokenType
                }
            }
        } catch (e: Exception) {
            logger.error("Update minimap image error", e)
        }
    }

    private fun freshImage() {
        val g = img!!.graphics as Graphics2D
        g.composite = CLEAR
        g.fillRect(0, 0, img!!.width, img!!.height)
    }

    private fun renderText(text: CharSequence, startX: Int, y: Int, color: Int): Int {
        var x = 0
        text.chars()
            .forEach {
                if (startX + x < config.width) {
                    renderChar(startX + x++, y, it, color)
                } else {
                    return@forEach
                }
            }
        return x
    }

    private fun renderChar(x: Int, y: Int, char: Int, color: Int) {
        if (config.clean) {
            renderClean(x, y, char, color)
        } else {
            renderAccurate(x, y, char, color)
        }
    }

    private fun renderClean(x: Int, y: Int, char: Int, color: Int) {
        val weight = when (char) {
            in 0..32 -> 0.0f
            in 33..126 -> 0.8f
            else -> 0.4f
        }

        if (weight == 0.0f) return

        when (config.pixelsPerLine) {
            1 -> // Cant show whitespace between lines any more. This looks rather ugly...
                setPixel(x, y + 1, color, weight * 0.6f)

            2 -> {
                // Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
                setPixel(x, y, color, weight * 0.3f)
                setPixel(x, y + 1, color, weight * 0.6f)
            }
            3 -> {
                // Three lines we make the top nearly empty, and fade the bottom a little too
                setPixel(x, y, color, weight * 0.1f)
                setPixel(x, y + 1, color, weight * 0.6f)
                setPixel(x, y + 2, color, weight * 0.6f)
            }
            4 -> {
                // Empty top line, Nice blend for everything else
                setPixel(x, y + 1, color, weight * 0.6f)
                setPixel(x, y + 2, color, weight * 0.6f)
                setPixel(x, y + 3, color, weight * 0.6f)
            }
        }
    }

    private fun renderAccurate(x: Int, y: Int, char: Int, color: Int) {
        val topWeight = GetTopWeight(char)
        val bottomWeight = GetBottomWeight(char)
        // No point rendering non visible characters.
        if (topWeight == 0.0f && bottomWeight == 0.0f) return

        when (config.pixelsPerLine) {
            1 -> // Cant show whitespace between lines any more. This looks rather ugly...
                setPixel(x, y + 1, color, ((topWeight + bottomWeight) / 2.0).toFloat())

            2 -> {
                // Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
                setPixel(x, y, color, topWeight * 0.5f)
                setPixel(x, y + 1, color, bottomWeight)
            }
            3 -> {
                // Three lines we make the top nearly empty, and fade the bottom a little too
                setPixel(x, y, color, topWeight * 0.3f)
                setPixel(x, y + 1, color, ((topWeight + bottomWeight) / 2.0).toFloat())
                setPixel(x, y + 2, color, bottomWeight * 0.7f)
            }
            4 -> {
                // Empty top line, Nice blend for everything else
                setPixel(x, y + 1, color, topWeight)
                setPixel(x, y + 2, color, ((topWeight + bottomWeight) / 2.0).toFloat())
                setPixel(x, y + 3, color, bottomWeight)
            }
        }
    }

    /**
     * mask out the alpha component and set it to the given value.
     * @param color         Color A
     * *
     * @param alpha     alpha percent from 0-1.
     * *
     * @return int color
     */
    private fun setPixel(x: Int, y: Int, color: Int, alpha: Float) {
        var a = alpha
        if (a > 1) a = color.toFloat()
        if (a < 0) a = 0f

        val unpackedColor = IntArray(4)
        // abgr is backwards?
        unpackedColor[3] = (a * 255).toInt()
        unpackedColor[0] = (color and 16711680) shr 16
        unpackedColor[1] = (color and 65280) shr 8
        unpackedColor[2] = (color and 255)

        img!!.raster.setPixel(x, y, unpackedColor)
    }

    class LineInfo internal constructor(var number: Int, var begin: Int, var end: Int)

    companion object {
        private val CLEAR = AlphaComposite.getInstance(AlphaComposite.CLEAR)
    }
}
