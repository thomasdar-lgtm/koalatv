# KoalaTV Relais

Programme Windows : le PC télécharge films et épisodes pour les appareils KoalaTV de la maison,
qui les lisent ensuite par le réseau local.

- Liaison au compte par le code de « Connecter un appareil ».
- File de téléchargements alimentée par les applis (Firebase `h/<compte>/q`), état publié dans `rl`, adresse du PC dans `r`.
- Un fichier à la fois ; pause automatique quand un appareil lit en ligne (présence `p` sans `l`/`dl`).
- Vidéos servies sur `http://<ip>:<port>/v/<clé>?t=<jeton>` (plages d'octets, lecture pendant le téléchargement).
- Page de réglages sur `http://127.0.0.1:<port>/` (accessible depuis le PC seulement).

Compilation : `GOOS=windows go build -ldflags "-H windowsgui" .` — tests : `go test -race ./...`
