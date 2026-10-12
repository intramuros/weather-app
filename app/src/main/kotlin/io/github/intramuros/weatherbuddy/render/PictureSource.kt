package io.github.intramuros.weatherbuddy.render

import android.content.Context
import io.github.intramuros.weatherbuddy.core.ArtStyle
import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.CustomStyleStore
import java.io.FileNotFoundException
import java.io.InputStream

/** Opens a style's pictures: the app's own from its assets, made ones from the phone's storage. */
class PictureSource(context: Context) {
    private val assets = context.applicationContext.assets
    private val customStyles = CustomStyleStore(context)

    /** @throws FileNotFoundException if the picture isn't there. */
    fun open(style: ArtStyle, picture: ScenePicture): InputStream = when (style) {
        is Style -> assets.open(style.scene(picture))
        is CustomStyle -> customStyles.file(style, picture).inputStream()
    }
}
