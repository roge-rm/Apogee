# Terra geography

Terra uses Earth’s measured coastlines, land relief, ocean bathymetry and Antarctic/Greenland ice-sheet surfaces from NOAA ETOPO 2022. Heights and depths use the same scale as horizontal distances: `Terra radius / 6,371,000`. For the 600 km Terra this is about 0.0942. Relief is not vertically exaggerated.

The global grid is sampled every 6 arc-minutes, about 1.05 km on Terra at the equator. Europe, the Andes, western North America and East Africa have 3 arc-minute detail. The Himalayas and Alps retain the source’s 1 arc-minute spacing, about 175 m on Terra. Finer grids blend into the global grid over one degree at their margins. Cubic interpolation joins samples continuously; longitudes wrap and the polar samples agree. Narrow summits and valleys below the dataset’s resolution are smoothed. Small seeded ground detail adds at most a few metres; it does not replace the measured mountain ranges.

Climate materials put deserts, forests, tundra and snow in broad Earth regions. Snow lines account for latitude and aridity. Greenland’s ice sheet and Antarctica are snowy; there are no trees on permanent ice. The northern pack ice uses the observed NSIDC March 2025 sea-ice extent, converted to a signed distance field. It follows the Arctic coasts and has a low solid surface, with a continuous margin into open water. This is a fixed winter ice outline, not a seasonal simulation.

The Cape’s existing ground and materials remain within 12 km of its pad and blend into the Earth terrain by 20 km. Its launch works, runway and harbour retain their positions. This is a small local exception to the Earth relief.

Terrain generation is 12. Old slow, low craft are reseated on the new ground. Parked craft from generations 9–11 whose land became ocean move to nearby dry ground with their surface attitude preserved. Orbiting craft and craft afloat on remaining ocean retain their positions. Multiplayer requires matching terrain generations.

`heavy build ./gradlew :core:terraAtlas` writes a world map, both polar views and relief maps of the Alps, Himalayas and Andes into `core/build/terra-atlas`.

## Data and attribution

- NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. [doi:10.25921/fd45-gt74](https://doi.org/10.25921/fd45-gt74). The subset used is the global 60 arc-second ice-surface GeoTIFF, version 1. [Product information](https://www.ncei.noaa.gov/products/etopo-global-relief-model).
- Fetterer, F., Knowles, K., Meier, W. N., Savoie, M., Windnagel, A. K. and Stafford, T. (2025). Sea Ice Index (G02135, Version 4). National Snow and Ice Data Center. [doi:10.7265/a98x-0f50](https://doi.org/10.7265/a98x-0f50). The subset used is Northern Hemisphere monthly sea-ice extent for March 2025, `N_202503_extent_v4.0.tif`. [Dataset information](https://nsidc.org/data/g02135/versions/4). Accessed 2026-10-13.

Rebuild the checked-in compact resources with:

```sh
uv run tools/import-earth.py build/earth-data/etopo.tif build/earth-data/arctic-extent.tif
```

Source downloads:

- [ETOPO GeoTIFF](https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/60s/60s_surface_elev_gtif/ETOPO_2022_v1_60s_N90W180_surface.tif), SHA-256 `9d27d4b8ea8e76977e2988bca667d7c8fa68b927355feffcddd6b4875a7fd08e`.
- [Arctic extent GeoTIFF](https://noaadata.apps.nsidc.org/NOAA/G02135/north/monthly/geotiff/03_Mar/N_202503_extent_v4.0.tif), SHA-256 `1f478c4acb361d94d7dbf1db78e0240f511f103ef9810742094bbcbcb25eb32e`.

The resources store signed 16-bit Earth metres (ice distance uses 100 m units), encoded with a two-dimensional predictor, zigzag varints and base64. Terrain sampling needs no network connection. Each source file’s identity and attribution are also included in resource metadata.

## Climate and Cape harbour (generation 12)

Desert and steppe boundaries now follow the observed 1991–2020 Köppen–Geiger climate map, rather than oval regional masks. The game stores a quarter-degree moisture field with bilinear sampling, a narrow transition between climate zones and periodic longitude wrapping. Tropical forest, savanna, steppe, desert and temperate classes supply moisture values; existing slope, snow, beach and local ground rules still choose the surface material. No network is needed in the game.

Source: Beck, H. E. et al. (2023), [High-resolution (1 km) Köppen–Geiger maps for 1901–2099 based on constrained CMIP6 projections](https://doi.org/10.1038/s41597-023-02549-6), Scientific Data 10, 724. [Dataset and licence](https://www.gloh2o.org/koppen/): Creative Commons Attribution 4.0. The input is the 1991–2020 1 km GeoTIFF, obtained from the [Stanford Natural Capital Project mirror](https://data.naturalcapitalalliance.stanford.edu/download/global/koppen_geiger_climatezones/koppen_geiger_climatezones_1991_2020_1km.tif). The resource records the source checksum. Regenerate with:

```sh
uv run tools/import-climate.py /path/to/koppen_geiger_climatezones_1991_2020_1km.tif
```

The Cape harbour is a designed branching coastal valley. Its coves narrow inland between long headlands; the broad mouth opens to the surrounding sea. Low hills replace the old rounded basin. Shelves run down to an 8–38 m floor, and the launch works retain their foundations. The approved shape is stored as a 70 × 80 m local height field at decimetre precision, sampled bilinearly and blended into the surrounding terrain over 2 km at the field's outer boundary. The field uses tangent coordinates aligned with the runway. Both collider and renderer read the same field.

Regenerate with `uv run tools/design-harbour.py`. The shoreline control points are in that script. `tools/data/cape-generation11.npz` holds the previous coast sampled before this redesign, at decimetre precision, so the designed terrain can meet the existing coast reproducibly. It is an authoring input, not a game dependency. The older procedural coast and climate detail remain available for save migration. Parked craft on flooded land move ashore; slow surface craft on filled harbour water move to nearby sea while retaining their waterline.

The Cape’s underwater discoveries lie off the redesigned harbour’s seaward side. The shelf, canyon, Farrow Seamount and vents retain their gameplay depth ranges; the nodule plain and trench sit beyond them. These are local designed sea-floor features. Their shaping fades at the shoreline without moving the approved coast. Existing arch and wreck craft move to their named locations while keeping their IDs and damage.
