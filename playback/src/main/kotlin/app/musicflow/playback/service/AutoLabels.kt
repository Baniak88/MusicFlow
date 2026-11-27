package app.musicflow.playback.service

import app.musicflow.data.repositories.ExploreCategory
import app.musicflow.playback.R

/** Explore category names for Android Auto (same texts as the Explore screen). */
internal fun autoCategoryLabel(c: ExploreCategory): Int = when (c) {
    ExploreCategory.RADIO_RMF_ON -> R.string.auto_cat_radio_rmf_on
    ExploreCategory.RADIO_ESKA -> R.string.auto_cat_radio_eska
    ExploreCategory.RADIO_LOCAL -> R.string.auto_cat_radio_local
    ExploreCategory.RADIO_WORLD -> R.string.auto_cat_radio_world
    ExploreCategory.RADIO_POP -> R.string.auto_cat_radio_pop
    ExploreCategory.RADIO_ROCK -> R.string.auto_cat_radio_rock
    ExploreCategory.RADIO_DANCE -> R.string.auto_cat_radio_dance
    ExploreCategory.RADIO_JAZZ -> R.string.auto_cat_radio_jazz
    ExploreCategory.RADIO_CLASSICAL -> R.string.auto_cat_radio_classical
    ExploreCategory.RADIO_NEWS -> R.string.auto_cat_radio_news
    ExploreCategory.ELECTRONIC -> R.string.auto_cat_electronic
    ExploreCategory.DANCE -> R.string.auto_cat_dance
    ExploreCategory.EDM -> R.string.auto_cat_edm
    ExploreCategory.HOUSE -> R.string.auto_cat_house
    ExploreCategory.TRANCE -> R.string.auto_cat_trance
    ExploreCategory.TECHNO -> R.string.auto_cat_techno
    ExploreCategory.POP -> R.string.auto_cat_pop
    ExploreCategory.ROCK -> R.string.auto_cat_rock
    ExploreCategory.HIP_HOP -> R.string.auto_cat_hiphop
    ExploreCategory.RNB -> R.string.auto_cat_rnb
    ExploreCategory.CHILL -> R.string.auto_cat_chill
    ExploreCategory.WORKOUT -> R.string.auto_cat_workout
    ExploreCategory.HITS_80S -> R.string.auto_cat_80s
    ExploreCategory.HITS_90S -> R.string.auto_cat_90s
    ExploreCategory.HITS_2000S -> R.string.auto_cat_2000s
    ExploreCategory.NEW_RELEASES -> R.string.auto_cat_new_releases
    ExploreCategory.UK_CHARTS -> R.string.auto_cat_uk_charts
    ExploreCategory.GLOBAL_CHARTS -> R.string.auto_cat_global_charts
}
