# Runtimes Windows : provenance, intégrité et redistribution

## Versions verrouillées (20 novembre 2026)

La source de vérité est `.github/windows-runtimes.json` :

| Runtime | Version / distribution | SHA-256 de l’archive |
| --- | --- | --- |
| FFmpeg | 9.1 essentials win64, GyanD/codexffmpeg | `835b9a733445ba7a56a37a26cf7bde84e2f3ce7da2a8ec984276bb2abb1b922c` |
| VLC | 3.0.24 win64, VideoLAN | `1ed59c09152e78aff84663fa76efab26057716576db74332858810e5ef54ae1f` |

FFmpeg : URL d’asset figée sous `github.com/GyanD/codexffmpeg/releases/download/9.1/`.
Le digest et la taille **36 671 021 octets** ont été relevés via l’API GitHub du tag 9.1.
Le corps de cette release indique le code FFmpeg `https://github.com/FFmpeg/FFmpeg/commit/74e4393595`.
Gyan est un fournisseur de builds tiers, pas l’équipe officielle FFmpeg : ne pas présenter ce binaire
comme un binaire signé ou construit par l’équipe FFmpeg elle-même.

VLC : URL figée `https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.7z`.
Empreinte publiée dans le fichier `.7z.sha256` officiel, récupéré via HTTPS dans
[le job de provenance du run 44751662213](https://github.com/Boitier13/NewPipe/actions/runs/44751662213).
La réponse était un tableau d’octets dont le décodage ASCII donne l’empreinte épinglée.
Il s’agit d’une racine de confiance HTTPS envers l’éditeur, **pas** d’une signature indépendante
validée avec une clé distribuée hors bande. La vérification SHA-256 assure ensuite que les builds
suivants n’acceptent que ces mêmes octets, même si l’URL ou le serveur changent de contenu.

## Barrières avant utilisation

1. URL HTTPS, hôte initial autorisé, version explicite et digest figé ; aucun `latest`/`last` ni repli.
2. Taille bornée ; taille exacte supplémentaire pour FFmpeg.
3. **SHA-256 vérifié avant toute extraction ou exécution**. Un écart bloque le build.
4. `7z t` doit réussir ; listing inspecté avant extraction : refus chemins absolus, `..`, ADS Windows,
   liens, nombre d’entrées excessif et taille décompressée supérieure à 2 Gio.
5. Structure attendue vérifiée après extraction et aucun reparse point accepté.
6. Version FFmpeg contrôlée ; DLL/plugins/licence VLC requis ; smoke test natif depuis la distribution.
7. Publication du dossier uniquement après vérification des deux archives. Un dossier de sortie déjà
   présent provoque une erreur : **aucune suppression ni écrasement d’un ancien bundle**.

Les téléchargements et fichiers de diagnostic restent dans un dossier temporaire unique du runner.
Ils ne sont pas suivis par Git ; l’agent n’a pas exécuté de suppression de fichiers utilisateur.
La CI part d’un checkout propre. Pour un build local avec un bundle déjà généré, conserver ce bundle
et utiliser un checkout propre de la même révision ; ne pas contourner les validations de chemins.
Le manifeste est livré avec les ressources de l’application.

## Licences et limites de redistribution

Le script conserve désormais **l’intégralité** de chaque distribution extraite (et non plus seulement
un exécutable et des DLL isolées) : notices, documentation, licences et composants associés restent
avec les runtimes. Cela augmente la taille des artefacts. La copie de `ffmpeg.exe` à la racine du
sous-dossier FFmpeg conserve le contrat de détection historique, tandis que `bin/` reste intact.

- FFmpeg comporte des composants LGPL/GPL selon les options de compilation ; le build essentials
  Gyan doit être traité comme une distribution GPL avec ses dépendances activées, pas supposé LGPL
  sur la seule base du nom FFmpeg. Les notices fournies doivent être lues avec la configuration
  `ffmpeg -version` / `-buildconf` du binaire réellement distribué.
- VLC/libVLC et ses plugins comportent plusieurs licences. Ne pas conclure que toute la distribution
  est couverte par une seule mention LGPL de libVLC. Les notices VideoLAN et les licences des modules
  conservés s’appliquent.
- Le manifeste, les URL et les notices ne constituent pas à eux seuls une offre valide de sources
  correspondantes. **Avant diffusion publique**, réunir les sources correspondant aux binaires et
  dépendances, les scripts/configurations de construction requis et le mode de mise à disposition
  conforme, puis conserver l’ensemble avec les artefacts de release. Une URL FFmpeg amont ne couvre
  pas automatiquement les dépendances et recettes propres au fournisseur.
- Le présent changement améliore la conservation des informations ; il ne certifie pas juridiquement
  la conformité complète. Aucune signature Authenticode ou signature indépendante des archives
  n’est prétendue validée.

## Mise à jour future

Ne pas changer uniquement la version ou récupérer un nouveau digest au moment de chaque build.
Choisir une release exacte, contrôler la source du digest et les changements de licences/configuration,
réviser le manifeste en revue de code, exécuter les tests négatifs puis les builds et smoke tests.
Une empreinte calculée après un téléchargement non authentifié ne suffit pas à en prouver l’origine.
