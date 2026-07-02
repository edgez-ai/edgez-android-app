package ai.edgez.edgez

enum class NodeMapMarker(
    val id: String,
    val organicMapsStyle: String?,
    val label: String,
    val colorArgb: Long?,
) {
    DEFAULT("default", null, "Default", null),
    RED("red", "placemark-red", "Red", 0xFFE53935),
    BLUE("blue", "placemark-blue", "Blue", 0xFF1E88E5),
    PURPLE("purple", "placemark-purple", "Purple", 0xFF8E24AA),
    YELLOW("yellow", "placemark-yellow", "Yellow", 0xFFFDD835),
    PINK("pink", "placemark-pink", "Pink", 0xFFD81B60),
    BROWN("brown", "placemark-brown", "Brown", 0xFF795548),
    GREEN("green", "placemark-green", "Green", 0xFF43A047),
    ORANGE("orange", "placemark-orange", "Orange", 0xFFFB8C00),
    DEEP_PURPLE("deep_purple", "placemark-deeppurple", "Deep purple", 0xFF5E35B1),
    LIGHT_BLUE("light_blue", "placemark-lightblue", "Light blue", 0xFF039BE5),
    CYAN("cyan", "placemark-cyan", "Cyan", 0xFF00ACC1),
    TEAL("teal", "placemark-teal", "Teal", 0xFF00897B),
    LIME("lime", "placemark-lime", "Lime", 0xFFC0CA33),
    DEEP_ORANGE("deep_orange", "placemark-deeporange", "Deep orange", 0xFFF4511E),
    GRAY("gray", "placemark-gray", "Gray", 0xFF757575),
    BLUE_GRAY("blue_gray", "placemark-bluegray", "Blue gray", 0xFF546E7A);

    companion object {
        fun fromId(id: String?): NodeMapMarker {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }

        fun normalize(id: String?): String = fromId(id).id
    }
}
