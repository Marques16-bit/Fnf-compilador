package com.ports.fnfcompiler

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ports.fnfcompiler.ui.App
import javax.imageio.ImageIO

private object Resources

fun main() = application {
    val icon = BitmapPainter(ImageIO.read(Resources::class.java.getResourceAsStream("/icon.png")).toComposeImageBitmap())
    Window(
        onCloseRequest = ::exitApplication,
        title = "FNF Compiler",
        icon = icon,
        state = rememberWindowState(width = 760.dp, height = 900.dp)
    ) {
        App()
    }
}
