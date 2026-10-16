package one.meshcore.shared.map

import androidx.compose.runtime.Composable
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_region_subdivision_au_act
import one.meshcore.shared.resources.settings_region_subdivision_au_nsw
import one.meshcore.shared.resources.settings_region_subdivision_au_nt
import one.meshcore.shared.resources.settings_region_subdivision_au_qld
import one.meshcore.shared.resources.settings_region_subdivision_au_sa
import one.meshcore.shared.resources.settings_region_subdivision_au_tas
import one.meshcore.shared.resources.settings_region_subdivision_au_vic
import one.meshcore.shared.resources.settings_region_subdivision_au_wa
import one.meshcore.shared.resources.settings_region_subdivision_us_ak
import one.meshcore.shared.resources.settings_region_subdivision_us_al
import one.meshcore.shared.resources.settings_region_subdivision_us_ar
import one.meshcore.shared.resources.settings_region_subdivision_us_az
import one.meshcore.shared.resources.settings_region_subdivision_us_ca
import one.meshcore.shared.resources.settings_region_subdivision_us_co
import one.meshcore.shared.resources.settings_region_subdivision_us_ct
import one.meshcore.shared.resources.settings_region_subdivision_us_dc
import one.meshcore.shared.resources.settings_region_subdivision_us_de
import one.meshcore.shared.resources.settings_region_subdivision_us_fl
import one.meshcore.shared.resources.settings_region_subdivision_us_ga
import one.meshcore.shared.resources.settings_region_subdivision_us_hi
import one.meshcore.shared.resources.settings_region_subdivision_us_ia
import one.meshcore.shared.resources.settings_region_subdivision_us_id
import one.meshcore.shared.resources.settings_region_subdivision_us_il
import one.meshcore.shared.resources.settings_region_subdivision_us_in
import one.meshcore.shared.resources.settings_region_subdivision_us_ks
import one.meshcore.shared.resources.settings_region_subdivision_us_ky
import one.meshcore.shared.resources.settings_region_subdivision_us_la
import one.meshcore.shared.resources.settings_region_subdivision_us_ma
import one.meshcore.shared.resources.settings_region_subdivision_us_md
import one.meshcore.shared.resources.settings_region_subdivision_us_me
import one.meshcore.shared.resources.settings_region_subdivision_us_mi
import one.meshcore.shared.resources.settings_region_subdivision_us_mn
import one.meshcore.shared.resources.settings_region_subdivision_us_mo
import one.meshcore.shared.resources.settings_region_subdivision_us_ms
import one.meshcore.shared.resources.settings_region_subdivision_us_mt
import one.meshcore.shared.resources.settings_region_subdivision_us_nc
import one.meshcore.shared.resources.settings_region_subdivision_us_nd
import one.meshcore.shared.resources.settings_region_subdivision_us_ne
import one.meshcore.shared.resources.settings_region_subdivision_us_nh
import one.meshcore.shared.resources.settings_region_subdivision_us_nj
import one.meshcore.shared.resources.settings_region_subdivision_us_nm
import one.meshcore.shared.resources.settings_region_subdivision_us_nv
import one.meshcore.shared.resources.settings_region_subdivision_us_ny
import one.meshcore.shared.resources.settings_region_subdivision_us_oh
import one.meshcore.shared.resources.settings_region_subdivision_us_ok
import one.meshcore.shared.resources.settings_region_subdivision_us_or
import one.meshcore.shared.resources.settings_region_subdivision_us_pa
import one.meshcore.shared.resources.settings_region_subdivision_us_ri
import one.meshcore.shared.resources.settings_region_subdivision_us_sc
import one.meshcore.shared.resources.settings_region_subdivision_us_sd
import one.meshcore.shared.resources.settings_region_subdivision_us_tn
import one.meshcore.shared.resources.settings_region_subdivision_us_tx
import one.meshcore.shared.resources.settings_region_subdivision_us_ut
import one.meshcore.shared.resources.settings_region_subdivision_us_va
import one.meshcore.shared.resources.settings_region_subdivision_us_vt
import one.meshcore.shared.resources.settings_region_subdivision_us_wa
import one.meshcore.shared.resources.settings_region_subdivision_us_wi
import one.meshcore.shared.resources.settings_region_subdivision_us_wv
import one.meshcore.shared.resources.settings_region_subdivision_us_wy
import org.jetbrains.compose.resources.stringResource

/** Localized region label for Settings and onboarding, matching iOS `RegionalAreas.displayName`. */
@Composable
fun regionDisplayName(region: RegionSelection): String =
    RegionalAreas.displayName(region, platformPreferredLanguage(), subdivisionNameMap())

/** Localized ISO α-2 country name in the app language. */
@Composable
fun countryDisplayName(code: String): String = RegionalAreas.countryDisplayName(code, platformPreferredLanguage())

/** Localized ISO 3166-2 subdivision name, falling back to the English Settings.strings value. */
@Composable
fun subdivisionDisplayName(code: String): String =
    RegionalAreas.subdivisionDisplayName(code, subdivisionNameMap()) ?: code

@Composable
private fun subdivisionNameMap(): Map<String, String> = mapOf(
    "US-AL" to stringResource(Res.string.settings_region_subdivision_us_al),
    "US-AK" to stringResource(Res.string.settings_region_subdivision_us_ak),
    "US-AZ" to stringResource(Res.string.settings_region_subdivision_us_az),
    "US-AR" to stringResource(Res.string.settings_region_subdivision_us_ar),
    "US-CA" to stringResource(Res.string.settings_region_subdivision_us_ca),
    "US-CO" to stringResource(Res.string.settings_region_subdivision_us_co),
    "US-CT" to stringResource(Res.string.settings_region_subdivision_us_ct),
    "US-DE" to stringResource(Res.string.settings_region_subdivision_us_de),
    "US-DC" to stringResource(Res.string.settings_region_subdivision_us_dc),
    "US-FL" to stringResource(Res.string.settings_region_subdivision_us_fl),
    "US-GA" to stringResource(Res.string.settings_region_subdivision_us_ga),
    "US-HI" to stringResource(Res.string.settings_region_subdivision_us_hi),
    "US-ID" to stringResource(Res.string.settings_region_subdivision_us_id),
    "US-IL" to stringResource(Res.string.settings_region_subdivision_us_il),
    "US-IN" to stringResource(Res.string.settings_region_subdivision_us_in),
    "US-IA" to stringResource(Res.string.settings_region_subdivision_us_ia),
    "US-KS" to stringResource(Res.string.settings_region_subdivision_us_ks),
    "US-KY" to stringResource(Res.string.settings_region_subdivision_us_ky),
    "US-LA" to stringResource(Res.string.settings_region_subdivision_us_la),
    "US-ME" to stringResource(Res.string.settings_region_subdivision_us_me),
    "US-MD" to stringResource(Res.string.settings_region_subdivision_us_md),
    "US-MA" to stringResource(Res.string.settings_region_subdivision_us_ma),
    "US-MI" to stringResource(Res.string.settings_region_subdivision_us_mi),
    "US-MN" to stringResource(Res.string.settings_region_subdivision_us_mn),
    "US-MS" to stringResource(Res.string.settings_region_subdivision_us_ms),
    "US-MO" to stringResource(Res.string.settings_region_subdivision_us_mo),
    "US-MT" to stringResource(Res.string.settings_region_subdivision_us_mt),
    "US-NE" to stringResource(Res.string.settings_region_subdivision_us_ne),
    "US-NV" to stringResource(Res.string.settings_region_subdivision_us_nv),
    "US-NH" to stringResource(Res.string.settings_region_subdivision_us_nh),
    "US-NJ" to stringResource(Res.string.settings_region_subdivision_us_nj),
    "US-NM" to stringResource(Res.string.settings_region_subdivision_us_nm),
    "US-NY" to stringResource(Res.string.settings_region_subdivision_us_ny),
    "US-NC" to stringResource(Res.string.settings_region_subdivision_us_nc),
    "US-ND" to stringResource(Res.string.settings_region_subdivision_us_nd),
    "US-OH" to stringResource(Res.string.settings_region_subdivision_us_oh),
    "US-OK" to stringResource(Res.string.settings_region_subdivision_us_ok),
    "US-OR" to stringResource(Res.string.settings_region_subdivision_us_or),
    "US-PA" to stringResource(Res.string.settings_region_subdivision_us_pa),
    "US-RI" to stringResource(Res.string.settings_region_subdivision_us_ri),
    "US-SC" to stringResource(Res.string.settings_region_subdivision_us_sc),
    "US-SD" to stringResource(Res.string.settings_region_subdivision_us_sd),
    "US-TN" to stringResource(Res.string.settings_region_subdivision_us_tn),
    "US-TX" to stringResource(Res.string.settings_region_subdivision_us_tx),
    "US-UT" to stringResource(Res.string.settings_region_subdivision_us_ut),
    "US-VT" to stringResource(Res.string.settings_region_subdivision_us_vt),
    "US-VA" to stringResource(Res.string.settings_region_subdivision_us_va),
    "US-WA" to stringResource(Res.string.settings_region_subdivision_us_wa),
    "US-WV" to stringResource(Res.string.settings_region_subdivision_us_wv),
    "US-WI" to stringResource(Res.string.settings_region_subdivision_us_wi),
    "US-WY" to stringResource(Res.string.settings_region_subdivision_us_wy),
    "AU-ACT" to stringResource(Res.string.settings_region_subdivision_au_act),
    "AU-NSW" to stringResource(Res.string.settings_region_subdivision_au_nsw),
    "AU-NT" to stringResource(Res.string.settings_region_subdivision_au_nt),
    "AU-QLD" to stringResource(Res.string.settings_region_subdivision_au_qld),
    "AU-SA" to stringResource(Res.string.settings_region_subdivision_au_sa),
    "AU-TAS" to stringResource(Res.string.settings_region_subdivision_au_tas),
    "AU-VIC" to stringResource(Res.string.settings_region_subdivision_au_vic),
    "AU-WA" to stringResource(Res.string.settings_region_subdivision_au_wa),
)
