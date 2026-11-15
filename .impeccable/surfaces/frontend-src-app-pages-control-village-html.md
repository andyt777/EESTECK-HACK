---
version: 1
slug: "frontend-src-app-pages-control-village-html"
primary_target: "frontend/src/app/pages/control/village.html"
related_targets: ["frontend/src/app/pages/control/village.ts","frontend/src/app/pages/control/village.scss"]
---

# Village (Control Center) — upload space

Scope: route `/` (Control Center), component `frontend/src/app/pages/control/village.*`. Mode: Experience (the jury watches the pipeline happen inside the scene); the rest of the app stays Operate and only inherits the palette.
Audience: hackathon jury during the live demo; task: drop a file on the mascot and watch it be classified, routed, stored and verified.
Constraints: every movement is driven by real SSE events; the four category buildings open their category on click/Enter; storages show real status and latency; reduced-motion mode keeps state changes.
Build path: code-led (no image generation available).

## Direction contract
THESIS: The upload space is a small green-energy town in daylight where PPCastor, a beaver line-worker, hauls each file like a log down the path to its building and the energy line then carries it to storage. It refuses the dark control-room with glowing nodes that this category always ships.
OWN-WORLD: Baby-blue sky over white ground; pale-green hills and round two-tone trees; wind turbines turning slowly; solar strips on pink roofs; a creek on the left with PPCastor's lodge of stacked logs as the entry. Navy ink for text and outlines, bubblegum pink for the active line and accents, white buildings. Flat geometric vector, one stroke weight, no glow halos.
STORY: The jury sees where files enter (the lodge), who handles them (PPCastor), the four buildings (categories) and the three storages (data buildings), then watches one drop travel the whole way and land "Stocat și verificat".
FIRST VIEWPORT: Full-bleed scene, 1200x680 viewBox. Lodge + creek left, PPCastor on the plaza left-centre with the speech bubble, four buildings stacked centre, three storage buildings right, turbines and trees on the horizon behind; the drop target is PPCastor himself.
FORM: pinned whole by the user, seed: none (pinned) — "robotul vreau sa fie inlocuit de un castor pe care sa l cheme PPCastor, sa fie mascota, fundalul sa fie un fel de oras cu energie verde, copaci etc"; "Vreau albastrul schimbat cu baby blue, mai mult alb dar sa si pastram acel roz"; "Duce fișierul ca pe un buștean"; "Lucrător energetic".
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Signature interaction: on drop, PPCastor lifts the file as a log, walks the path to the category building, sets it down at the door; the pink energy line then runs from the building to the chosen storage and the packet travels it.
Motion grammar: walking with a small step bob, turbine blades rotating slowly, everything else still; ease-out, no idle sparkle.
