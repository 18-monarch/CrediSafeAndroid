package com.credisafe.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.credisafe.mobile.domain.LatLngPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Keyless native map used only as a visual aid. Safety and XP decisions never
 * depend on rendered tiles; they are made from telemetry and server-confirmed
 * road context. OpenFreeMap supplies the style and OSM-derived vector tiles.
 */
@Composable
fun OpenMobilityMap(
    route: List<LatLngPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                fun renderRoute() {
                    val points = route.map { LatLng(it.lat, it.lng) }
                    if (points.isEmpty()) return
                    map.clear()
                    if (points.size >= 2) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(points)
                                .color(android.graphics.Color.rgb(0, 230, 118))
                                .width(7f),
                        )
                    }
                    map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(points.last(), 15.0),
                        650,
                    )
                }


                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                if (map.style == null) {
                    map.setStyle(BuildConfig.MAP_STYLE_URL) { renderRoute() }
                } else {
                    renderRoute()
                }
            }
        },
    )
}
