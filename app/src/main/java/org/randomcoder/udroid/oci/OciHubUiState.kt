package org.randomcoder.udroid.oci

sealed interface OciHubCatalogueState {
    data object Loading : OciHubCatalogueState

    data class Ready(
        val snapshot: OciHubCatalogSnapshot,
        val platform: OciPlatform,
    ) : OciHubCatalogueState

    data class Failed(val message: String) : OciHubCatalogueState
}

sealed interface OciHubTagsState {
    data object Idle : OciHubTagsState

    data object Loading : OciHubTagsState

    data class Ready(val snapshot: OciHubTagSnapshot) : OciHubTagsState

    data class Failed(val message: String) : OciHubTagsState
}
