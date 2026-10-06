package com.zion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// ------------------------------------------------------------------ basics

@Composable
fun Txt(
    text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color? = null,
    maxLines: Int = Int.MAX_VALUE, ellipsis: Boolean = false, align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.let { s -> var r = if (color != null) s.copy(color = color) else s; if (align != null) r = r.copy(textAlign = align); r },
        maxLines = if (ellipsis) 1 else maxLines,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Z.TextMuted, size: Float = 10f) =
    Txt(text, Z.Overline.copy(fontSize = size.sp, color = color), modifier)

/** A click with no ripple; [pressed] reports the touch so the element can show its "hover" look. */
@Composable
fun Modifier.tap(source: MutableInteractionSource, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)

@Composable
fun rememberSource() = remember { MutableInteractionSource() }

// ------------------------------------------------------------------ cards

/** Large glass card that acts as a button (CardButton). */
@Composable
fun CardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    radius: Dp = 16.dp,
    background: Color? = null,
    border: Color? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(background ?: if (pressed) Z.GlassHover else Z.Glass)
            .border(1.dp, border ?: if (pressed) Z.StrokeHover else Z.Stroke, shape)
            .tap(src, enabled, onClick)
            .padding(padding),
        content = content,
    )
}

/** Static glass panel. */
@Composable
fun Glass(
    modifier: Modifier = Modifier, radius: Dp = 16.dp, padding: PaddingValues = PaddingValues(14.dp, 12.dp),
    background: Color = Z.Glass, border: Color = Z.Stroke, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(modifier.clip(shape).background(background).border(1.dp, border, shape).padding(padding), content = content)
}

/** Small rounded label (Pill). */
@Composable
fun Pill(text: String, size: Float = 9.5f, modifier: Modifier = Modifier, bg: Color = Z.AccentSoft, fg: Color = Z.AccentHi) {
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Txt(text, Z.text(size.sp, FontWeight.Bold, fg), maxLines = 1)
    }
}

// ------------------------------------------------------------------ buttons

/** Square glass icon button (IconBtn / IconBtnDanger). */
@Composable
fun IconBtn(
    path: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 32.dp, iconSize: Dp = 15.dp,
    danger: Boolean = false, enabled: Boolean = true, stroke: Boolean = false, iconHeight: Dp? = null,
) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val bg = when { pressed && danger -> Color(0x1FFB7185); pressed -> Z.GlassHover; else -> Z.Glass }
    val bd = when { pressed && danger -> Color(0x59FB7185); pressed -> Z.StrokeHover; else -> Z.Stroke }
    val fg = when { pressed && danger -> Z.Danger; pressed -> Z.TextPrimary; else -> Z.TextSecondary }
    Box(
        modifier.size(size).alpha(if (enabled) 1f else 0.35f).clip(shape).background(bg).border(1.dp, bd, shape).tap(src, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (stroke) PathIcon(path, stroke = fg, strokeWidth = 1.7.dp)
        else PathIcon(path, width = iconSize, height = iconHeight ?: iconSize, fill = fg)
    }
}

@Composable
fun BackBtn(onClick: () -> Unit) = IconBtn(Paths.Back, onClick, stroke = true)

/** Gradient button (PrimaryBtn). */
@Composable
fun PrimaryBtn(
    onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 40.dp, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(height).clip(shape).background(Z.AccentGradient).tap(src, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.12f)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    }
}

@Composable
fun PrimaryBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 40.dp, plus: Boolean = false) =
    PrimaryBtn(onClick, modifier, height) {
        if (plus) {
            PathIcon(Paths.Plus, stroke = Color.White, strokeWidth = 1.8.dp)
            Spacer(Modifier.width(9.dp))
        }
        Txt(text, Z.text(12.5.sp, FontWeight.SemiBold, Color.White))
    }

/** Glass button (GhostBtn). */
@Composable
fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 40.dp, size: Float = 12.5f) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(height).clip(shape).background(if (pressed) Z.GlassHover else Z.Glass)
            .border(1.dp, if (pressed) Z.StrokeHover else Z.Stroke, shape).tap(src, true, onClick),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Z.text(size.sp, FontWeight.SemiBold, if (pressed) Z.TextPrimary else Z.TextSecondary)) }
}

/** Tinted accent button (TonalBtn): paste, copy, undo. */
@Composable
fun TonalBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 36.dp, size: Float = 12f, minWidth: Dp = 0.dp) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(height).widthIn(min = minWidth).clip(shape).background(if (pressed) Color(0x408B7DFF) else Z.AccentSoft)
            .border(1.dp, if (pressed) Z.Accent else Z.AccentStroke, shape).tap(src, true, onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Z.text(size.sp, FontWeight.SemiBold, if (pressed) Color.White else Z.AccentHi), maxLines = 1) }
}

/** Solid red button (DangerBtn): the confirming step of a destructive action. */
@Composable
fun DangerBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 40.dp, size: Float = 12.5f) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(height).clip(shape).background(Z.DangerSolid).tap(src, true, onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.12f)))
        Txt(text, Z.text(size.sp, FontWeight.SemiBold, Color.White), maxLines = 1)
    }
}

/** Row action inside a card (star, edit, delete). */
@Composable
fun RowAction(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) Z.GlassHover else Color.Transparent).tap(src, true, onClick),
        contentAlignment = Alignment.Center, content = content,
    )
}

// ------------------------------------------------------------------ switches

@Composable
private fun SwitchTrack(checked: Boolean, width: Dp, height: Dp, thumb: Dp) {
    val travel = width - thumb - 6.dp
    val x by animateDpAsState(if (checked) travel else 0.dp, tween(160), label = "thumb")
    val onAlpha by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "track")
    val shape = RoundedCornerShape(height / 2)
    Box(Modifier.size(width, height).clip(shape).background(Color(0x24FFFFFF)), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.matchParentSize().alpha(onAlpha).background(Z.AccentGradient))
        Box(
            Modifier.padding(start = 3.dp).offset(x = x).size(thumb).clip(CircleShape)
                .background(if (checked) Color.White else Color(0xFFC9CFDD))
        )
    }
}

/** Quick-setting tile on the dashboard: label + switch (QuickToggle). */
@Composable
fun QuickToggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val border = when { checked -> Z.AccentStroke; pressed -> Z.StrokeHover; else -> Z.Stroke }
    Row(
        modifier.heightIn(min = 38.dp).clip(shape).background(if (checked) Z.FocusBg else Z.Glass).border(1.dp, border, shape)
            .tap(src) { onChange(!checked) }.padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // With large system text the label wraps to a second line rather than being cut off
        Txt(text, Z.text(11.5.sp, FontWeight.SemiBold, if (checked) Z.TextPrimary else Z.TextSecondary), Modifier.weight(1f).padding(vertical = 4.dp), maxLines = 2)
        Spacer(Modifier.width(8.dp))
        SwitchTrack(checked, 30.dp, 18.dp, 12.dp)
    }
}

/** Full-width settings row: title, explanation and a switch (SettingRow). */
@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (pressed) Z.GlassHover else Z.Glass)
            .border(1.dp, if (pressed) Z.StrokeHover else Z.Stroke, shape).tap(src) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Txt(title, Z.text(13.sp, FontWeight.SemiBold))
            Spacer(Modifier.height(3.dp))
            Txt(subtitle, Z.text(11.sp, color = Z.TextMuted))
        }
        SwitchTrack(checked, 36.dp, 20.dp, 14.dp)
    }
}

// ------------------------------------------------------------------ inputs

/** Text box (TextBox style): glass, placeholder in Tag, accent border while focused. */
@Composable
fun ZField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, height: Dp = 36.dp,
    keyboard: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null,
) {
    val src = rememberSource()
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        interactionSource = src,
        textStyle = Z.text(12.5.sp),
        cursorBrush = SolidColor(Z.AccentHi),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.height(height),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().clip(shape).background(if (focused) Z.FocusBg else Z.Glass)
                    .border(1.dp, if (focused) Z.Accent else Z.Stroke, shape).padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Txt(placeholder, Z.text(12.sp, color = Z.TextMuted), ellipsis = true)
                inner()
            }
        },
    )
}

@Composable
fun FieldLabel(text: String) = Txt(text, Z.FieldLabel, Modifier.padding(start = 2.dp, bottom = 5.dp))

/** Drop-down list (the dark ComboBox). */
@Composable
fun ZDropdown(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(shape).background(Z.Glass)
                .border(1.dp, if (open) Z.Accent else Z.Stroke, shape).tap(rememberSource()) { open = true }.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(items.getOrElse(selected) { "" }, Z.text(12.sp, FontWeight.SemiBold), Modifier.weight(1f), ellipsis = true)
            PathIcon(Paths.DropArrow, stroke = if (open) Z.AccentHi else Z.TextSecondary)
        }
        if (open) {
            Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true), offset = androidx.compose.ui.unit.IntOffset(0, 120)) {
                Column(
                    Modifier.widthIn(min = 140.dp).clip(RoundedCornerShape(12.dp)).background(Z.Surface)
                        .border(1.dp, Z.SheetBorder, RoundedCornerShape(12.dp)).padding(4.dp)
                ) {
                    items.forEachIndexed { i, label ->
                        val sel = i == selected
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (sel) Z.AccentSoft else Color.Transparent)
                                .tap(rememberSource()) { open = false; onSelect(i) }.padding(horizontal = 10.dp, vertical = 9.dp)
                        ) { Txt(label, Z.text(12.sp, color = if (sel) Z.AccentHi else Color(0xFFD5DAE8))) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sheets & bars

/** Bottom sheet over the current screen: dimmed backdrop (tap closes), card slides up and fades in. */
@Composable
fun BottomSheet(visible: Boolean, onDismiss: () -> Unit, padding: PaddingValues = PaddingValues(14.dp, 10.dp, 14.dp, 14.dp),
                maxHeight: Dp = Dp.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Box(Modifier.fillMaxSize().background(Z.Backdrop).tap(rememberSource(), true, onDismiss), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { 60 } + fadeIn(tween(180)),
            ) {
                val shape = RoundedCornerShape(22.dp)
                Column(
                    Modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()
                        .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
                        .clip(shape).background(Z.Surface).border(1.dp, Z.SheetBorder, shape)
                        .tap(rememberSource(), true) {} // taps on the card do not close it
                        .padding(padding)
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)))
                    content()
                }
            }
        }
    }
}

/** Snackbar with an action ("Отменить", "Вернуть"). */
@Composable
fun UndoBar(visible: Boolean, message: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(shape).background(Z.Surface).border(1.dp, Z.AccentStroke, shape)
                .padding(start = 14.dp, top = 9.dp, end = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(message, Z.text(11.5.sp), Modifier.weight(1f).padding(end = 10.dp), ellipsis = true)
            TonalBtn(action, onAction, height = 28.dp, size = 11f)
        }
    }
}

/** Header of a screen: back button, title, optional count pill, actions on the right. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, count: String? = null, titleMaxWidth: Dp = Dp.Unspecified,
                 actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BackBtn(onBack)
        Spacer(Modifier.width(12.dp))
        Txt(title, Z.ScreenTitle, if (titleMaxWidth != Dp.Unspecified) Modifier.widthIn(max = titleMaxWidth) else Modifier, ellipsis = true)
        if (count != null) {
            Spacer(Modifier.width(9.dp))
            Pill(count, 10.5f)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

/** An empty-state message in the middle of a list. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, Z.text(14.sp, FontWeight.SemiBold, Z.TextSecondary), align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Txt(text, Z.text(11.5.sp, color = Z.TextMuted), align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Animated color helper. */
@Composable
fun animatedColor(target: Color) = animateColorAsState(target, tween(200), label = "c").value
