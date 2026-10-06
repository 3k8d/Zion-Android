package com.zion.app.core

/** Country of a server from its name (flag emoji, words), its country field or its host name. Same rules as Windows. */
object Countries {

    fun extractEmojiFlag(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var i = 0
        while (i < text.length - 1) {
            val cp1 = text.codePointAt(i)
            val len1 = Character.charCount(cp1)
            if (cp1 in 0x1F1E6..0x1F1FF) {
                val next = i + len1
                if (next < text.length) {
                    val cp2 = text.codePointAt(next)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        return "${'a' + (cp1 - 0x1F1E6)}${'a' + (cp2 - 0x1F1E6)}"
                    }
                }
            }
            i += len1
        }
        return null
    }

    fun matchHostPattern(host: String?): String {
        if (host.isNullOrBlank()) return "un"
        var clean = host.trim().lowercase()
        if (clean.contains(':')) clean = clean.split(':')[0]

        fun starts(vararg p: String) = p.any { clean.startsWith(it) }
        when {
            starts("pt-", "pt.", "pt_") -> return "pt"
            starts("al-", "al.", "al_") -> return "al"
            starts("rm-", "ro-", "ro.", "ro_") -> return "ro"
            starts("de-", "de.", "de_") -> return "de"
            starts("nl-", "nl.", "nl_") -> return "nl"
            starts("en-", "uk-", "gb-", "gb.", "gb_") -> return "gb"
            starts("cz-", "cz.", "cz_") -> return "cz"
            starts("au-", "at-", "at.", "at_") -> return "at"
            starts("si-", "ch-", "ch.", "ch_") -> return "ch"
            starts("sw-", "se-", "se.", "se_") -> return "se"
            starts("li-", "lt-", "lt.", "lt_") -> return "lt"
            starts("fr-", "fr.", "fr_") -> return "fr"
            starts("pl-", "pl.", "pl_") -> return "pl"
            starts("kz-", "kz.", "kz_") -> return "kz"
            starts("sp-", "es-", "es.", "es_") -> return "es"
            starts("us-", "us.", "us_") -> return "us"
            starts("tr-", "tr.", "tr_") -> return "tr"
            starts("id-", "id.", "in-", "in.", "in_") -> return "in"
            starts("jp-", "jp.") -> return "jp"
            starts("sg-", "sg.") -> return "sg"
            starts("ru-", "ru.") -> return "ru"
        }

        // Universal prefix token check (e.g. fi-hel1.example.com -> fi)
        val tokens = clean.split('-', '.', '_').filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && tokens[0].length == 2 && tokens[0][0].isLetter() && tokens[0][1].isLetter()) {
            val t0 = tokens[0]
            if (!nameRu(t0).equals(t0, ignoreCase = true)) return if (t0 == "uk") "gb" else t0
        }

        val tlds = listOf(
            ".nl" to "nl", ".de" to "de", ".fi" to "fi", ".se" to "se", ".pl" to "pl", ".ru" to "ru", ".su" to "ru",
            ".fr" to "fr", ".uk" to "gb", ".ch" to "ch", ".at" to "at", ".cz" to "cz", ".es" to "es", ".it" to "it",
            ".jp" to "jp", ".sg" to "sg", ".hk" to "hk", ".ca" to "ca", ".ae" to "ae", ".kz" to "kz", ".ua" to "ua",
            ".tr" to "tr", ".no" to "no", ".dk" to "dk", ".be" to "be", ".ee" to "ee", ".lv" to "lv", ".lt" to "lt",
            ".pt" to "pt", ".al" to "al", ".ro" to "ro", ".in" to "in", ".us" to "us",
        )
        for ((suffix, code) in tlds) if (clean.endsWith(suffix)) return code
        return "un"
    }

    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "nl" to listOf("netherlands", "нидерланд", "голланди", "amsterdam", "амстердам", "rotterdam"),
        "de" to listOf("germany", "германи", "deutschland", "frankfurt", "франкфурт", "berlin", "берлин", "munich", "мюнхен"),
        "us" to listOf("united states", "usa", "сша", "america", "америк", "new york", "нью-йорк", "los angeles", "miami", "dallas", "даллас"),
        "fi" to listOf("finland", "финлянд", "helsinki", "хельсинки"),
        "se" to listOf("sweden", "швеци", "stockholm", "стокгольм"),
        "pl" to listOf("poland", "польш", "warsaw", "варшава", "krakow"),
        "ru" to listOf("russia", "росси", "moscow", "москва", "petersburg", "петербург", "спб"),
        "gb" to listOf("united kingdom", "великобритан", "england", "англи", "london", "лондон", "uk"),
        "fr" to listOf("france", "франци", "paris", "париж"),
        "tr" to listOf("turkey", "турци", "istanbul", "стамбул", "ankara"),
        "kz" to listOf("kazakhstan", "казахстан", "almaty", "astana", "алматы", "астана"),
        "ch" to listOf("switzerland", "швейцари", "zurich", "цюрих", "geneva"),
        "at" to listOf("austria", "австри", "vienna", "вена"),
        "cz" to listOf("czech", "чехи", "prague", "прага"),
        "es" to listOf("spain", "испани", "madrid", "мадрид", "barcelona"),
        "it" to listOf("italy", "итали", "rome", "рим", "milan", "милан"),
        "jp" to listOf("japan", "япони", "tokyo", "токио", "osaka"),
        "sg" to listOf("singapore", "сингапур"),
        "hk" to listOf("hong kong", "гонконг", "hkg"),
        "ca" to listOf("canada", "канада", "toronto", "торонто", "montreal"),
        "ae" to listOf("united arab", "emirates", "оаэ", "dubai", "дубай", "abu dhabi"),
        "pt" to listOf("portugal", "португали", "lisbon", "лиссабон"),
        "al" to listOf("albania", "албани", "tirana", "тирана"),
        "ro" to listOf("romania", "румыни", "bucharest", "бухарест"),
        "in" to listOf("india", "инди", "mumbai", "мумбаи", "delhi", "дели"),
        "md" to listOf("moldova", "молдов", "chisinau", "кишинев", "кишинёв"),
        "bg" to listOf("bulgaria", "болгари", "sofia", "софия"),
        "cy" to listOf("cyprus", "кипр", "nicosia"),
        "rs" to listOf("serbia", "серби", "belgrade", "белград"),
        "hu" to listOf("hungary", "венгри", "budapest", "будапешт"),
        "gr" to listOf("greece", "греци", "athens", "афины"),
        "ie" to listOf("ireland", "ирланди", "dublin", "дублин"),
        "kr" to listOf("south korea", "корея", "korea", "seoul", "сеул"),
        "br" to listOf("brazil", "бразили", "sao paulo"),
        "sk" to listOf("slovakia", "словаки", "bratislava"),
        "si" to listOf("slovenia", "словени", "ljubljana"),
        "hr" to listOf("croatia", "хорвати", "zagreb"),
        "is" to listOf("iceland", "исланди", "reykjavik"),
        "lu" to listOf("luxembourg", "люксембург"),
        "tw" to listOf("taiwan", "тайван", "taipei"),
        "lv" to listOf("latvia", "латви", "riga", "рига"),
        "lt" to listOf("lithuania", "литва", "vilnius", "вильнюс"),
        "ee" to listOf("estonia", "эстони", "tallinn", "таллин"),
        "no" to listOf("norway", "норвеги", "oslo", "осло"),
        "dk" to listOf("denmark", "дани", "copenhagen"),
        "be" to listOf("belgium", "бельги", "brussels"),
        "ua" to listOf("ukraine", "украин", "kyiv", "киев"),
        "ge" to listOf("georgia", "грузи", "tbilisi"),
        "am" to listOf("armenia", "армени", "yerevan"),
        "il" to listOf("israel", "израиль", "tel aviv"),
        "au" to listOf("australia", "австрали", "sydney"),
        "az" to listOf("azerbaijan", "азербайджан", "baku"),
        "uz" to listOf("uzbekistan", "узбекистан", "tashkent"),
        "kg" to listOf("kyrgyzstan", "киргизи", "кыргызстан"),
        "tj" to listOf("tajikistan", "таджикистан"),
        "by" to listOf("belarus", "беларус", "белорусси", "minsk"),
        "ar" to listOf("argentina", "аргентин"),
        "cl" to listOf("chile", "чили"),
        "za" to listOf("south africa", "юар"),
        "th" to listOf("thailand", "таиланд", "тайланд", "bangkok"),
        "vn" to listOf("vietnam", "вьетнам"),
        "id" to listOf("indonesia", "индонези", "jakarta"),
        "my" to listOf("malaysia", "малайзи"),
        "ph" to listOf("philippines", "филиппин"),
        "nz" to listOf("new zealand", "новая зеланди"),
        "mx" to listOf("mexico", "мексик"),
        "eg" to listOf("egypt", "египет"),
        "sa" to listOf("saudi", "саудовск"),
    )

    fun matchSemanticKeyword(text: String): String {
        val lower = text.lowercase()
        for ((code, words) in KEYWORDS) if (words.any { lower.contains(it) }) return code
        return "un"
    }

    private fun isTwoLetters(s: String) = s.length == 2 && s[0].isLetter() && s[1].isLetter()

    fun resolveIsoCode(text: String?): String = resolveIsoCode(text, null, null)

    fun resolveIsoCode(name: String?, country: String?, host: String?): String {
        if ((!name.isNullOrBlank() && (name.contains("Авто выбор", true) || name.contains("Автовыбор", true) || name.contains("Auto", true))) ||
            (!host.isNullOrBlank() && host.trim().startsWith("auto.", true))
        ) return "auto"

        if (!name.isNullOrBlank()) {
            extractEmojiFlag(name)?.let { return it }
            val sem = matchSemanticKeyword(name)
            if (sem != "un") return sem
        }
        if (!country.isNullOrBlank()) {
            extractEmojiFlag(country)?.let { return it }
            val sem = matchSemanticKeyword(country)
            if (sem != "un") return sem
            val c = country.trim().lowercase()
            if (isTwoLetters(c)) return if (c == "uk") "gb" else c
        }
        if (!host.isNullOrBlank()) {
            val h = matchHostPattern(host)
            if (h != "un") return h
        }
        if (!name.isNullOrBlank()) {
            val n = name.trim().lowercase()
            if (isTwoLetters(n)) return if (n == "uk") "gb" else n
        }
        return ""
    }

    fun nameRu(iso: String): String = when (iso.lowercase()) {
        "nl" -> "Нидерланды"; "de" -> "Германия"; "us" -> "США"; "fi" -> "Финляндия"; "se" -> "Швеция"
        "pl" -> "Польша"; "ru" -> "Россия"; "gb", "uk" -> "Великобритания"; "fr" -> "Франция"; "tr" -> "Турция"
        "kz" -> "Казахстан"; "ch" -> "Швейцария"; "at" -> "Австрия"; "cz" -> "Чехия"; "es" -> "Испания"
        "it" -> "Италия"; "jp" -> "Япония"; "sg" -> "Сингапур"; "hk" -> "Гонконг"; "ca" -> "Канада"
        "ae" -> "ОАЭ"; "md" -> "Молдова"; "ro" -> "Румыния"; "bg" -> "Болгария"; "cy" -> "Кипр"
        "rs" -> "Сербия"; "hu" -> "Венгрия"; "gr" -> "Греция"; "ie" -> "Ирландия"; "pt" -> "Португалия"
        "al" -> "Албания"; "kr" -> "Южная Корея"; "in" -> "Индия"; "br" -> "Бразилия"; "sk" -> "Словакия"
        "si" -> "Словения"; "hr" -> "Хорватия"; "is" -> "Исландия"; "lu" -> "Люксембург"; "tw" -> "Тайвань"
        "lv" -> "Латвия"; "lt" -> "Литва"; "ee" -> "Эстония"; "no" -> "Норвегия"; "dk" -> "Дания"
        "be" -> "Бельгия"; "ua" -> "Украина"; "ge" -> "Грузия"; "am" -> "Армения"; "il" -> "Израиль"
        "au" -> "Австралия"; "az" -> "Азербайджан"; "uz" -> "Узбекистан"; "kg" -> "Кыргызстан"; "tj" -> "Таджикистан"
        "by" -> "Беларусь"; "ar" -> "Аргентина"; "cl" -> "Чили"; "za" -> "ЮАР"; "th" -> "Таиланд"
        "vn" -> "Вьетнам"; "id" -> "Индонезия"; "my" -> "Малайзия"; "ph" -> "Филиппины"; "nz" -> "Новая Зеландия"
        "mx" -> "Мексика"; "eg" -> "Египет"; "sa" -> "Саудовская Аравия"; "cn" -> "Китай"; "auto" -> "Авто выбор"
        else -> iso.uppercase()
    }

    /** GeoIP answers like "United States of America, Frankfurt am Main" → short, clean form. */
    fun normalizeLocation(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        fun r(pattern: String, to: String) { s = Regex(pattern, RegexOption.IGNORE_CASE).replace(s, to) }
        r("\\s+am Main\\b", ""); r("\\s+an der Oder\\b", ""); r("\\s+an der Donau\\b", "")
        r("\\bSaint Petersburg\\b", "St. Petersburg")
        r("\\bUnited States of America\\b", "USA"); r("\\bUnited States\\b", "USA")
        r("\\bUnited Kingdom of Great Britain and Northern Ireland\\b", "UK"); r("\\bUnited Kingdom\\b", "UK")
        r("\\bRussian Federation\\b", "Russia"); r("\\bThe Netherlands\\b", "Netherlands"); r("\\bUnited Arab Emirates\\b", "UAE")
        return s.trim()
    }
}
