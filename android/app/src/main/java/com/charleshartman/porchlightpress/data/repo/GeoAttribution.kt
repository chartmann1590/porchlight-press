package com.charleshartman.porchlightpress.data.repo

/**
 * Gazetteer + postal data licenses/attribution (MASTER_PLAN G4, §14).
 *
 * The app resolves places and ZIPs on-device from the published slices
 * (public/locations/{country}.json, {country}-postal.json), built at
 * pipeline time from the sources below. Coordinates are never derived from
 * gazetteer centroids onto stories. Phase 6 renders this in About.
 */
object GeoAttribution {
    const val CENSUS_LICENSE = "US Census Bureau Gazetteer (places + ZCTA): public domain (Title 13 U.S.C.)."
    const val GEONAMES_LICENSE = "GeoNames cities15000 + postal data: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)."
    const val CENSUS_URL = "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/"
    const val GEONAMES_URL = "https://download.geonames.org/export/dump/"

    val lines: List<String> = listOf(CENSUS_LICENSE, GEONAMES_LICENSE)
}
