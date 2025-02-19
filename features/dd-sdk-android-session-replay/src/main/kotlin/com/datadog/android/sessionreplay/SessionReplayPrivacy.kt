/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay

import android.os.Build
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toolbar
import androidx.appcompat.widget.SwitchCompat
import com.datadog.android.sessionreplay.internal.recorder.mapper.BasePickerMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.ButtonMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.CheckBoxMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.CheckedTextViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.ImageViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MapperTypeWrapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskCheckBoxMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskCheckedTextViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskInputTextViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskNumberPickerMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskRadioButtonMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskSeekBarWireframeMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskSwitchCompatMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.MaskTextViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.NumberPickerMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.RadioButtonMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.SeekBarWireframeMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.SwitchCompatMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.TextViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.UnsupportedViewMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.WebViewWireframeMapper
import com.datadog.android.sessionreplay.internal.recorder.mapper.WireframeMapper
import com.datadog.android.sessionreplay.internal.recorder.obfuscator.rules.AllowObfuscationRule
import com.datadog.android.sessionreplay.internal.utils.ImageViewUtils
import com.datadog.android.sessionreplay.utils.ColorStringFormatter
import com.datadog.android.sessionreplay.utils.DefaultColorStringFormatter
import com.datadog.android.sessionreplay.utils.DefaultViewBoundsResolver
import com.datadog.android.sessionreplay.utils.DefaultViewIdentifierResolver
import com.datadog.android.sessionreplay.utils.DrawableToColorMapper
import com.datadog.android.sessionreplay.utils.ViewBoundsResolver
import com.datadog.android.sessionreplay.utils.ViewIdentifierResolver
import androidx.appcompat.widget.Toolbar as AppCompatToolbar

/**
 * Defines the Session Replay privacy policy when recording the sessions.
 * @see SessionReplayPrivacy.ALLOW
 * @see SessionReplayPrivacy.MASK
 * @see SessionReplayPrivacy.MASK_USER_INPUT
 *
 */
enum class SessionReplayPrivacy {
    /** Does not apply any privacy rule on the recorded data with an exception for strong privacy
     * sensitive EditTextViews.
     * The EditTextViews which have email, password, postal address or phone number
     * inputType will be masked no matter what the privacy option with space-preserving "x" mask
     * (each char individually)
     **/
    ALLOW,

    /**
     *  Masks all the elements. All the characters in texts will be replaced by X, images will be
     *  replaced with just a placeholder and switch buttons, check boxes and radio buttons will also
     *  be masked. This is the default privacy rule.
     **/
    MASK,

    /**
     * Masks most form fields such as inputs, checkboxes, radio buttons, switchers, sliders, etc.
     * while recording all other text as is. Inputs are replaced with three asterisks (***).
     */
    MASK_USER_INPUT;

    private val viewIdentifierResolver: ViewIdentifierResolver = DefaultViewIdentifierResolver
    private val colorStringFormatter: ColorStringFormatter = DefaultColorStringFormatter
    private val viewBoundsResolver: ViewBoundsResolver = DefaultViewBoundsResolver
    private val drawableToColorMapper: DrawableToColorMapper = DrawableToColorMapper.getDefault()

    @Suppress("LongMethod")
    internal fun mappers(): List<MapperTypeWrapper> {
        val unsupportedViewMapper =
            UnsupportedViewMapper(
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        val imageViewMapper =
            ImageViewMapper(
                ImageViewUtils,
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        val textMapper: TextViewMapper
        val buttonMapper: ButtonMapper
        val checkedTextViewMapper: CheckedTextViewMapper
        val checkBoxMapper: CheckBoxMapper
        val radioButtonMapper: RadioButtonMapper
        val switchCompatMapper: SwitchCompatMapper
        val seekBarMapper: SeekBarWireframeMapper?
        val numberPickerMapper: BasePickerMapper?
        val webViewWireframeMapper = WebViewWireframeMapper(
            viewIdentifierResolver,
            colorStringFormatter,
            viewBoundsResolver,
            drawableToColorMapper
        )
        when (this) {
            ALLOW -> {
                textMapper = TextViewMapper(
                    AllowObfuscationRule(),
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                buttonMapper = ButtonMapper(textMapper)
                checkedTextViewMapper = CheckedTextViewMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                checkBoxMapper = CheckBoxMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                radioButtonMapper = RadioButtonMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                switchCompatMapper = SwitchCompatMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                seekBarMapper = getSeekBarMapper()
                numberPickerMapper = getNumberPickerMapper()
            }

            MASK -> {
                textMapper = MaskTextViewMapper(
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                buttonMapper = ButtonMapper(textMapper)
                checkedTextViewMapper = MaskCheckedTextViewMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                checkBoxMapper = MaskCheckBoxMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                radioButtonMapper = MaskRadioButtonMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                switchCompatMapper = MaskSwitchCompatMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                seekBarMapper = getMaskSeekBarMapper()
                numberPickerMapper = getMaskNumberPickerMapper()
            }

            MASK_USER_INPUT -> {
                textMapper = MaskInputTextViewMapper(
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                buttonMapper = ButtonMapper(textMapper)
                checkedTextViewMapper = MaskCheckedTextViewMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                checkBoxMapper = MaskCheckBoxMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                radioButtonMapper = MaskRadioButtonMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                switchCompatMapper = MaskSwitchCompatMapper(
                    textMapper,
                    viewIdentifierResolver,
                    colorStringFormatter,
                    viewBoundsResolver,
                    drawableToColorMapper
                )
                seekBarMapper = getMaskSeekBarMapper()
                numberPickerMapper = getMaskNumberPickerMapper()
            }
        }
        val mappersList = mutableListOf(
            MapperTypeWrapper(SwitchCompat::class.java, switchCompatMapper.toGenericMapper()),
            MapperTypeWrapper(RadioButton::class.java, radioButtonMapper.toGenericMapper()),
            MapperTypeWrapper(CheckBox::class.java, checkBoxMapper.toGenericMapper()),
            MapperTypeWrapper(CheckedTextView::class.java, checkedTextViewMapper.toGenericMapper()),
            MapperTypeWrapper(Button::class.java, buttonMapper.toGenericMapper()),
            MapperTypeWrapper(TextView::class.java, textMapper.toGenericMapper()),
            MapperTypeWrapper(ImageView::class.java, imageViewMapper.toGenericMapper()),
            MapperTypeWrapper(AppCompatToolbar::class.java, unsupportedViewMapper.toGenericMapper()),
            MapperTypeWrapper(WebView::class.java, webViewWireframeMapper.toGenericMapper())
        )

        mappersList.add(
            0,
            MapperTypeWrapper(Toolbar::class.java, unsupportedViewMapper.toGenericMapper())
        )

        seekBarMapper?.let {
            mappersList.add(0, MapperTypeWrapper(SeekBar::class.java, it.toGenericMapper()))
        }
        numberPickerMapper?.let {
            mappersList.add(
                0,
                MapperTypeWrapper(
                    NumberPicker::class.java,
                    it.toGenericMapper()
                )
            )
        }
        return mappersList
    }

    private fun getMaskSeekBarMapper(): MaskSeekBarWireframeMapper? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MaskSeekBarWireframeMapper(
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        } else {
            null
        }
    }

    private fun getSeekBarMapper(): SeekBarWireframeMapper? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SeekBarWireframeMapper(
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        } else {
            null
        }
    }

    private fun getNumberPickerMapper(): BasePickerMapper? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NumberPickerMapper(
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        } else {
            null
        }
    }

    private fun getMaskNumberPickerMapper(): BasePickerMapper? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MaskNumberPickerMapper(
                viewIdentifierResolver,
                colorStringFormatter,
                viewBoundsResolver,
                drawableToColorMapper
            )
        } else {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun WireframeMapper<*, *>.toGenericMapper(): WireframeMapper<View, *> {
        return this as WireframeMapper<View, *>
    }
}
