package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure local-radio command parser, mirroring the Swift `CLILocalCommandParserTests`. */
class CliLocalCommandParserTest {
    private fun command(command: CliLocalCommand): CliLocalParseResult = CliLocalParseResult.Command(command)

    private fun invalid(error: CliLocalParseError): CliLocalParseResult = CliLocalParseResult.Invalid(error)

    @Test
    fun parsesValidCommands() {
        val cases: List<Pair<String, CliLocalCommand>> = listOf(
            "clock" to CliLocalCommand.Clock,
            "CLOCK" to CliLocalCommand.Clock,
            "clock sync" to CliLocalCommand.ClockSync,
            "clock SYNC" to CliLocalCommand.ClockSync,
            "ver" to CliLocalCommand.Ver,
            "board" to CliLocalCommand.Board,
            "advert" to CliLocalCommand.Advert(flood = false),
            "advert.zerohop" to CliLocalCommand.Advert(flood = false),
            "floodadv" to CliLocalCommand.Advert(flood = true),
            "reboot" to CliLocalCommand.Reboot,
            "get name" to CliLocalCommand.GetKey(CliLocalKey.NAME),
            "get bat" to CliLocalCommand.GetKey(CliLocalKey.BAT),
            "get public.key" to CliLocalCommand.GetKey(CliLocalKey.PUBLIC_KEY),
            "get multi.acks" to CliLocalCommand.GetKey(CliLocalKey.MULTI_ACKS),
            "get path.hash.mode" to CliLocalCommand.GetKey(CliLocalKey.PATH_HASH_MODE),
            "GET RADIO" to CliLocalCommand.GetKey(CliLocalKey.RADIO),
            "set name Field Node 3" to CliLocalCommand.SetName("Field Node 3"),
            "set tx 22" to CliLocalCommand.SetTxPower(22),
            "set tx -9" to CliLocalCommand.SetTxPower(-9),
            "set lat 47.49" to CliLocalCommand.SetLatitude(47.49),
            "set lon -120.33" to CliLocalCommand.SetLongitude(-120.33),
            "set freq 869.525" to CliLocalCommand.SetFrequency(869.525),
            "set multi.acks 1" to CliLocalCommand.SetMultiAcks(1),
            "set path.hash.mode 2" to CliLocalCommand.SetPathHashMode(2),
            "set radio 869.525,250,11,5" to
                CliLocalCommand.SetRadio(869.525, 250.0, 11, 5),
            "set radio 869.525, 250, 11, 5" to
                CliLocalCommand.SetRadio(869.525, 250.0, 11, 5),
        )
        for ((line, expected) in cases) {
            assertEquals(command(expected), CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun preservesNameCaseAndInnerSpaces() {
        assertEquals(
            command(CliLocalCommand.SetName("Mixed CASE")),
            CliLocalCommandParser.parse("set name  Mixed CASE  "),
        )
    }

    @Test
    fun unrecognizedFirstWordsFallThrough() {
        val lines =
            listOf("", "   ", "help", "clear", "session list", "login node", "logout", "nodes", "channels", "wat")
        for (line in lines) {
            assertEquals(CliLocalParseResult.NotLocal, CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun dangerousCommandsRequireConfirmation() {
        assertEquals(true, CliLocalCommand.Reboot.requiresConfirmation)
        assertEquals(true, CliLocalCommand.SetFrequency(869.5).requiresConfirmation)
        assertEquals(true, CliLocalCommand.SetRadio(869.5, 250.0, 11, 5).requiresConfirmation)
        assertEquals(false, CliLocalCommand.SetTxPower(22).requiresConfirmation)
        assertEquals(false, CliLocalCommand.Clock.requiresConfirmation)
    }

    @Test
    fun getWithoutKeyIsBadArguments() {
        for (line in listOf("get", "get   ")) {
            assertEquals(invalid(CliLocalParseError.BadArguments(CliLocalUsage.GET)), CliLocalCommandParser.parse(line))
        }
    }

    @Test
    fun setWithoutValueIsBadArguments() {
        for (line in listOf("set", "set name", "set name    ")) {
            assertEquals(invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET)), CliLocalCommandParser.parse(line))
        }
    }

    @Test
    fun readOnlyKeysAreNotSettable() {
        for (line in listOf("set public.key abcd", "set bat 42")) {
            assertEquals(invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET)), CliLocalCommandParser.parse(line))
        }
    }

    @Test
    fun nonNumericSetValuesAreBadArguments() {
        for (line in listOf("set tx abc", "set lat notanumber", "set freq xyz")) {
            assertEquals(invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET)), CliLocalCommandParser.parse(line))
        }
    }

    @Test
    fun outOfRangeSetValuesAreReported() {
        val lines = listOf(
            "set tx 200",
            "set lat 200",
            "set lon -300",
            "set freq 3000",
            "set multi.acks 5",
            "set path.hash.mode 9",
        )
        for (line in lines) {
            assertEquals(invalid(CliLocalParseError.ValueOutOfRange), CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun malformedSetRadioIsBadArguments() {
        for (line in listOf("set radio 869.525", "set radio 869.525,250,11", "set radio a,b,c,d")) {
            assertEquals(
                invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET_RADIO)),
                CliLocalCommandParser.parse(line),
            )
        }
    }

    @Test
    fun outOfRangeSetRadioFieldsAreReported() {
        val lines = listOf(
            "set radio 3000,250,11,5",
            "set radio 869.525,600,11,5",
            "set radio 869.525,250,3,5",
            "set radio 869.525,250,11,9",
        )
        for (line in lines) {
            assertEquals(invalid(CliLocalParseError.ValueOutOfRange), CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun bareGetFallsThroughToCustomVar() {
        val cases: List<Pair<String, CliLocalCommand>> = listOf(
            "get custom" to CliLocalCommand.GetCustomVars,
            "GET CUSTOM" to CliLocalCommand.GetCustomVars,
            "get gps" to CliLocalCommand.GetCustomVar("gps"),
            "get GPS" to CliLocalCommand.GetCustomVar("GPS"),
            "get gps extra" to CliLocalCommand.GetCustomVar("gps"),
            "get _tx" to CliLocalCommand.GetCustomVar("tx"),
            "get _custom" to CliLocalCommand.GetCustomVar("custom"),
            "get __x" to CliLocalCommand.GetCustomVar("_x"),
        )
        for ((line, expected) in cases) {
            assertEquals(command(expected), CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun bareSetFallsThroughToCustomVar() {
        val cases: List<Pair<String, CliLocalCommand>> = listOf(
            "set gps 1" to CliLocalCommand.SetCustomVar("gps", "1"),
            "set gps_interval 60" to CliLocalCommand.SetCustomVar("gps_interval", "60"),
            "set label Field Node 3" to CliLocalCommand.SetCustomVar("label", "Field Node 3"),
            "set url a:b" to CliLocalCommand.SetCustomVar("url", "a:b"),
            "set wifi_ssid MyNetwork" to CliLocalCommand.SetCustomVar("wifi_ssid", "MyNetwork"),
            "set wifi_ssid -" to CliLocalCommand.SetCustomVar("wifi_ssid", "-"),
            "set _tx 22" to CliLocalCommand.SetCustomVar("tx", "22"),
        )
        for ((line, expected) in cases) {
            assertEquals(command(expected), CliLocalCommandParser.parse(line), "line: $line")
        }
    }

    @Test
    fun typedKeysKeepPriorityOverCustomVarFallthrough() {
        assertEquals(command(CliLocalCommand.GetKey(CliLocalKey.TX)), CliLocalCommandParser.parse("get tx"))
        assertEquals(command(CliLocalCommand.SetTxPower(22)), CliLocalCommandParser.parse("set TX 22"))
    }

    @Test
    fun structurallyInvalidCustomVarTokensAreReported() {
        val lines = listOf("set a,b 1", "set a:b 1", "set gps 1,2", "get bad:key", "get bad,key")
        for (line in lines) {
            assertEquals(
                invalid(CliLocalParseError.InvalidCustomVarToken),
                CliLocalCommandParser.parse(line),
                "line: $line",
            )
        }
    }

    @Test
    fun oversizedCustomVarSetPairIsRejectedByByteLength() {
        // key(1) + ":" (1) + value counts UTF-8 bytes, not characters: "é" is 2 bytes.
        val overValue = "a" + "é".repeat(69) // 1 + 138 = 139 bytes
        assertEquals(141, "k:$overValue".encodeToByteArray().size)
        assertEquals(invalid(CliLocalParseError.InvalidCustomVarToken), CliLocalCommandParser.parse("set k $overValue"))
    }

    @Test
    fun customVarSetPairAtByteLimitIsAccepted() {
        val atLimit = "a" + "é".repeat(68) // 1 + 136 = 137 bytes
        assertEquals(139, "k:$atLimit".encodeToByteArray().size)
        assertEquals(command(CliLocalCommand.SetCustomVar("k", atLimit)), CliLocalCommandParser.parse("set k $atLimit"))
    }

    @Test
    fun missingCustomVarPiecesRenderBadArguments() {
        val lines = listOf("set gps", "set gps   ", "set _ 1", "get _")
        for (line in lines) {
            val usage = if (line.startsWith("get")) CliLocalUsage.GET else CliLocalUsage.SET
            assertEquals(
                invalid(CliLocalParseError.BadArguments(usage)),
                CliLocalCommandParser.parse(line),
                "line: $line",
            )
        }
    }

    @Test
    fun sensorIsNotALocalCommand() {
        for (line in listOf("sensor list", "sensor get gps", "sensor set gps 1")) {
            assertEquals(CliLocalParseResult.NotLocal, CliLocalCommandParser.parse(line), "line: $line")
        }
    }
}
