package dev.loglens.ui.mx

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults as MiuixProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 兼容占位：原 Material3 实验性注解，Miuix 侧无需 OptIn */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3Api

private fun Shape.toCornerRadius(): Dp = 16.dp

@Composable
private fun ProvideContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        MiuixLocalContentColor provides color,
        content = content
    )
}

/* ---------------- Text ---------------- */

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = TextStyle.Default,
) {
    val effectiveStyle = if (style == TextStyle.Default) MiuixTheme.textStyles.main else style
    MiuixText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        style = effectiveStyle
    )
}

/** 富文本重载：支持高亮 span（BasicText 直接消费 AnnotatedString） */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = TextStyle.Default,
) {
    val base = if (style == TextStyle.Default) MiuixTheme.textStyles.main else style
    val effective = base.copy(
        color = if (color == Color.Unspecified) base.color else color,
        fontSize = if (fontSize == TextUnit.Unspecified) base.fontSize else fontSize,
        fontStyle = fontStyle ?: base.fontStyle,
        fontWeight = fontWeight ?: base.fontWeight,
        fontFamily = fontFamily ?: base.fontFamily,
        letterSpacing = if (letterSpacing == TextUnit.Unspecified) base.letterSpacing else letterSpacing,
        textAlign = textAlign ?: base.textAlign,
        lineHeight = if (lineHeight == TextUnit.Unspecified) base.lineHeight else lineHeight
    )
    BasicText(
        text = text,
        modifier = modifier,
        style = effective,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

/* ---------------- Icon ---------------- */

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (tint == Color.Unspecified) {
        MiuixIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (tint == Color.Unspecified) {
        MiuixIcon(painter = painter, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(painter = painter, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

@Composable
fun Icon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (tint == Color.Unspecified) {
        MiuixIcon(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

/* ---------------- Card ---------------- */

class MxCardColors(val containerColor: Color, val contentColor: Color)

object CardDefaults {
    fun cardColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): MxCardColors = MxCardColors(containerColor, contentColor)
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: MxCardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val decorated = if (border != null) modifier.border(border, shape) else modifier
    val cardColors = if (colors.containerColor == Color.Unspecified) {
        MiuixCardDefaults.defaultColors()
    } else {
        MiuixCardDefaults.defaultColors(
            color = colors.containerColor,
            contentColor = if (colors.contentColor == Color.Unspecified) scheme.onSurfaceContainer else colors.contentColor
        )
    }
    MiuixCard(modifier = decorated, cornerRadius = shape.toCornerRadius(), colors = cardColors) {
        content()
    }
}

/* ---------------- Buttons ---------------- */

class MxButtonColors(val containerColor: Color, val contentColor: Color)

object ButtonDefaults {
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): MxButtonColors = MxButtonColors(containerColor, contentColor)

    fun textButtonColors(
        contentColor: Color = Color.Unspecified,
        containerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): MxButtonColors = MxButtonColors(containerColor, contentColor)

    fun outlinedButtonColors(
        contentColor: Color = Color.Unspecified,
        containerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): MxButtonColors = MxButtonColors(containerColor, contentColor)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: MxButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val buttonColors = if (colors.containerColor == Color.Unspecified) {
        MiuixButtonDefaults.buttonColorsPrimary()
    } else {
        MiuixButtonDefaults.buttonColors(
            color = colors.containerColor,
            contentColor = if (colors.contentColor == Color.Unspecified) scheme.onPrimary else colors.contentColor
        )
    }
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = shape.toCornerRadius(),
        colors = buttonColors,
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MxButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val textColors = if (colors.contentColor == Color.Unspecified) {
        MiuixButtonDefaults.textButtonColors()
    } else {
        MiuixButtonDefaults.textButtonColors(color = Color.Transparent, textColor = colors.contentColor)
    }
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.buttonColors(
            color = Color.Transparent,
            contentColor = textColors.textColor,
            disabledContentColor = textColors.disabledTextColor
        ),
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    border: BorderStroke? = null,
    colors: MxButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val stroke = border ?: BorderStroke(1.dp, scheme.outline)
    val contentColor = if (colors.contentColor == Color.Unspecified) scheme.primary else colors.contentColor
    val buttonColors = MiuixButtonDefaults.buttonColors(color = Color.Transparent, contentColor = contentColor)
    MiuixButton(
        onClick = onClick,
        modifier = modifier.border(stroke, shape),
        enabled = enabled,
        cornerRadius = shape.toCornerRadius(),
        colors = buttonColors,
        content = content
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    MiuixIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    MiuixFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (containerColor == Color.Unspecified) scheme.primary else containerColor
    ) {
        ProvideContentColor(if (contentColor == Color.Unspecified) scheme.onPrimary else contentColor) {
            content()
        }
    }
}

/* ---------------- Switch / Surface / Divider / Progress ---------------- */

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MiuixSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled
    )
}

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    MiuixSurface(
        modifier = modifier,
        shape = shape,
        color = if (color == Color.Unspecified) scheme.surface else color,
        contentColor = if (contentColor == Color.Unspecified) scheme.onSurface else contentColor,
        border = border,
        content = content
    )
}

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    thickness: Dp = 1.dp,
) {
    MiuixHorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = if (color == Color.Unspecified) MiuixTheme.colorScheme.dividerLine else color
    )
}

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 3.dp,
    trackColor: Color = Color.Unspecified,
) {
    val scheme = MiuixTheme.colorScheme
    MiuixCircularProgressIndicator(
        modifier = modifier,
        colors = MiuixProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = if (color == Color.Unspecified) scheme.primary else color
        ),
        strokeWidth = strokeWidth
    )
}

/* ---------------- Scaffold / TopAppBar / BottomAppBar ---------------- */

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit,
) {
    MiuixScaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        containerColor = if (containerColor == Color.Unspecified) MiuixTheme.colorScheme.background else containerColor,
        content = content
    )
}

class MxTopAppBarColors(
    val containerColor: Color,
    val titleContentColor: Color,
    val navigationIconContentColor: Color,
)

object TopAppBarDefaults {
    fun topAppBarColors(
        containerColor: Color = Color.Unspecified,
        titleContentColor: Color = Color.Unspecified,
        navigationIconContentColor: Color = Color.Unspecified,
        actionIconContentColor: Color = Color.Unspecified,
        scrolledContainerColor: Color = Color.Unspecified,
    ): MxTopAppBarColors = MxTopAppBarColors(containerColor, titleContentColor, navigationIconContentColor)
}

@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: MxTopAppBarColors = TopAppBarDefaults.topAppBarColors(),
) {
    val scheme = MiuixTheme.colorScheme
    val background = if (colors.containerColor == Color.Unspecified) scheme.surface else colors.containerColor
    val titleColor = if (colors.titleContentColor == Color.Unspecified) scheme.onSurface else colors.titleContentColor
    val navColor = if (colors.navigationIconContentColor == Color.Unspecified) titleColor else colors.navigationIconContentColor
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProvideContentColor(navColor) { navigationIcon() }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                ProvideContentColor(titleColor) { title() }
            }
            ProvideContentColor(titleColor) { actions() }
        }
    }
}

@Composable
fun BottomAppBar(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val background = if (containerColor == Color.Unspecified) scheme.surface else containerColor
    val foreground = if (contentColor == Color.Unspecified) scheme.onSurface else contentColor
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProvideContentColor(foreground) { content() }
        }
    }
}

/* ---------------- FilterChip ---------------- */

class MxFilterChipColors(
    val containerColor: Color,
    val labelColor: Color,
    val selectedContainerColor: Color,
    val selectedLabelColor: Color,
)

object FilterChipDefaults {
    fun filterChipColors(
        containerColor: Color = Color.Unspecified,
        labelColor: Color = Color.Unspecified,
        selectedContainerColor: Color = Color.Unspecified,
        selectedLabelColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledLabelColor: Color = Color.Unspecified,
    ): MxFilterChipColors = MxFilterChipColors(containerColor, labelColor, selectedContainerColor, selectedLabelColor)

    fun filterChipBorder(
        enabled: Boolean = true,
        selected: Boolean = false,
        borderColor: Color = Color.Unspecified,
        selectedBorderColor: Color = Color.Unspecified,
        disabledBorderColor: Color = Color.Unspecified,
        disabledSelectedBorderColor: Color = Color.Unspecified,
        borderWidth: Dp = 1.dp,
        selectedBorderWidth: Dp = 1.dp,
    ): BorderStroke {
        val color = if (selected) selectedBorderColor else borderColor
        return BorderStroke(if (selected) selectedBorderWidth else borderWidth, color)
    }
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MxFilterChipColors = FilterChipDefaults.filterChipColors(),
    border: BorderStroke? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(50),
) {
    val scheme = MiuixTheme.colorScheme
    val background = if (selected) {
        if (colors.selectedContainerColor == Color.Unspecified) scheme.primaryContainer else colors.selectedContainerColor
    } else {
        if (colors.containerColor == Color.Unspecified) Color.Transparent else colors.containerColor
    }
    val foreground = if (selected) {
        if (colors.selectedLabelColor == Color.Unspecified) scheme.onPrimaryContainer else colors.selectedLabelColor
    } else {
        if (colors.labelColor == Color.Unspecified) scheme.onSurface else colors.labelColor
    }
    val stroke = border ?: BorderStroke(1.dp, scheme.outline)
    val brush = stroke.brush
    val applied = if (brush is SolidColor && brush.value == Color.Unspecified) BorderStroke(stroke.width, scheme.outline) else stroke
    Row(
        modifier
            .clip(shape)
            .background(background, shape)
            .border(applied, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            ProvideContentColor(foreground) { leadingIcon() }
            Spacer(Modifier.width(6.dp))
        }
        ProvideContentColor(foreground) { label() }
    }
}

/* ---------------- OutlinedTextField ---------------- */

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: Any? = null,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MiuixTheme.colorScheme
    val baseStyle = if (textStyle == TextStyle.Default) MiuixTheme.textStyles.main else textStyle
    Column(modifier) {
        if (label != null) {
            Box(Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                ProvideContentColor(scheme.onSurfaceVariantSummary) { label() }
            }
        }
        MiuixSurface(
            shape = shape,
            color = scheme.secondaryContainer,
            contentColor = scheme.onSurface,
            border = BorderStroke(1.dp, if (isError) scheme.error else scheme.outline)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    ProvideContentColor(scheme.onSurfaceVariantSummary) { leadingIcon() }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        ProvideContentColor(scheme.onSurfaceVariantSummary) { placeholder() }
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        readOnly = readOnly,
                        textStyle = baseStyle.copy(color = scheme.onSurface),
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        cursorBrush = SolidColor(scheme.primary),
                        visualTransformation = visualTransformation,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions
                    )
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(8.dp))
                    ProvideContentColor(scheme.onSurfaceVariantSummary) { trailingIcon() }
                }
            }
        }
        if (supportingText != null) {
            Box(Modifier.padding(start = 6.dp, top = 4.dp)) {
                ProvideContentColor(scheme.onSurfaceVariantSummary) { supportingText() }
            }
        }
    }
}
