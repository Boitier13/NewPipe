# Correctifs avant essais Windows 10 — 20 novembre 2026

Ce rapport complète et actualise `boitiertube-audit-final.md`. Les constats de cet ancien audit
sont historiques : les protections ci-dessous ne s’y trouvaient pas encore.
Travail effectué uniquement sur `arena/1461b4cf-newpipe`. Aucune branche créée/supprimée,
aucun fichier du dépôt supprimé, aucune modification de `dev`, aucune migration amont.
L’ancienne pointe centrale `da4e029303` reste dans l’historique.

## 1. VLC et FFmpeg : versions, intégrité, provenance

### Réalisé

- FFmpeg **9.1 essentials win64 (Gyan)** et VLC **3.0.24 win64 (VideoLAN)** épinglés dans
  `.github/windows-runtimes.json`, URL exactes et SHA-256 fixes.
- Digest FFmpeg obtenu de l’API de la release GitHub ; digest VLC obtenu du `.sha256` officiel
  via le job de provenance du run 44751662213. La provenance, les empreintes complètes et les
  limites de confiance sont documentées dans `boitiertube-runtime-provenance.md`.
- Refus d’un hash différent **avant extraction/exécution**, taille bornée et taille exacte FFmpeg,
  test d’intégrité 7-Zip, inspection des chemins/liens et de la taille décompressée avant extraction.
- Pas de `latest`, pas de repli implicite vers une autre archive/version, pas de confiance accordée
  à la seule taille ou à une empreinte calculée sur place puis immédiatement acceptée.
- Dossier de sortie déjà existant refusé sans écrasement ; staging des deux runtimes avant publication.
- Distributions complètes conservées, avec leurs notices/licences, au lieu d’un sous-ensemble amputé.
  Contrats `ffmpeg/ffmpeg.exe`, DLL VLC et plugins maintenus. Cela augmente l’espace occupé.
- Manifeste et notes de provenance livrés avec les ressources.
- Tests PowerShell : bon digest accepté ; digest incorrect, taille incomplète, fichier vide,
  chemins d’archive dangereux, ADS, liens et taille décompressée excessive refusés.

### Limites / conditions de diffusion

La provenance initiale repose sur HTTPS envers GitHub/VideoLAN, pas sur une signature indépendante
validée hors bande. Les archives suivantes sont comparées aux empreintes figées en revue de code.
Ces empreintes ne constituent ni une analyse antivirus ni une preuve d’absence de CVE.

Les notices originales sont désormais conservées, mais **la conformité de redistribution n’est pas
certifiée**. Avant diffusion publique, réunir les sources correspondantes exactes, dépendances et
recettes de build, vérifier les obligations GPL/LGPL de chaque composant et le mode d’offre de sources.
Le code FFmpeg mentionné par Gyan est `74e4393595` ; cela ne couvre pas à lui seul les bibliothèques
liées ni les recettes du fournisseur. Pas de prétendue validation Authenticode.

## 2. Sauvegardes et restauration

### Revue et protections maintenues

Validation complète du ZIP, CRC, limites de taille, UTF-8/JSON, SQLite et schéma avant fermeture du
vivant ; snapshot WAL, sauvegarde des paramètres typés, rollback sur exceptions, refus d’écraser un
export existant, copies conservées en cas de récupération incomplète. Les 19 tests historiques restent
présents : archives invalides/corrompues/incomplètes, CRC, entrées supplémentaires, erreurs d’écriture,
préférences défaillantes et erreurs de rollback y sont couverts.

### Ajout : journal et redémarrage bloqué en cas de doute

Après les copies de secours forcées sur disque, un fichier `recovery-state.txt` est forcé à `pending`
**avant** la première fermeture/substitution. Il passe à `completed` seulement après réouverture réussie
ou rollback terminé. Une écriture de statut interrompue reste considérée comme indéterminée.

Avant ouverture/création initiale de la DB :
- un journal incomplet/inconnu, ou une ancienne paire de secours sans journal, bloque l’ouverture ;
- aucun choix implicite entre génération ancienne/nouvelle, aucune création silencieuse de base vide ;
- au démarrage graphique, un dialogue français indique de conserver les fichiers et le dossier concerné ;
- le contrôle est également effectué par le gateway DB, pas seulement par l’interface.

Quatre tests de `DesktopRecoveryJournalTest` couvrent les états incomplets/validés, les anciens secours,
et une interruption fatale simulée à quatre points de substitution. Les copies DB sont relues et
leur contenu original vérifié. Le test utilise un `Error` non récupéré par le rollback normal : ce
n’est ni un arrêt physique de machine ni un test end-to-end de `TerminateProcess`.

### Choix d’architecture non improvisé

La restauration n’est toujours pas une transaction atomique commune au système de fichiers et à
Java Preferences. Le nouveau comportement est **fail-closed**, pas une réparation automatique.
Une coupure matérielle avec cache de contrôleur disque ne peut pas être garantie sans essais dédiés.
La persistance des entrées de répertoire n’est pas une certification de résistance à toute panne.

Pour une récupération : fermer les instances, copier le **profil entier** et tous les dossiers de
secours vers un emplacement indépendant, conserver originaux/journaux/WAL, puis examiner la paire
`previous.db` + `previous-settings.json` ensemble sur la copie. Prochaine évolution proposée : outil
de diagnostic en lecture seule, puis reconstruction explicite d’un profil séparé après confirmation
utilisateur. Ne pas effacer un journal pour simplement débloquer le lancement, et ne pas choisir
arbitrairement un fichier DB sans les paramètres cohérents.

**Risque maintenu du mode installé** : Java Preferences reste au nœud racine historique. Son isolation
nécessite une migration avec attribution des clés et préservation des clés étrangères à BoitierTube.
Aucune migration automatique ou suppression de ces réglages n’a été ajoutée. Pour les premiers essais,
utiliser un compte de test ou le nouveau profil portable vierge, pas le profil quotidien.

## 3. Mode portable explicite, sans migration

### Réalisé

Le ZIP contient `Start-BoitierTube-Portable.cmd`. Ce lanceur choisit `BOITIERTUBE_PORTABLE_ROOT` pour
son seul processus (`setlocal`). Le titre et la tooltip indiquent **BoitierTube — mode portable**.
Un lancement direct normal continue à utiliser les emplacements historiques installés ; les MSI/EXE
ne reçoivent pas le lanceur portable lors du packaging.

Sous le dossier extrait :
- `data/newpipe.db`, journaux, fichiers temporaires de téléchargement ;
- `data/preferences.checked-json`, pas le registre/nœud Java installé ;
- téléchargements par défaut sous `data/downloads/Video` et `Audio`.

Aucune donnée n’est copiée/déplacée depuis le profil installé. Le transfert via sauvegarde/restauration
reste explicite et remplace le profil **cible** après validation ; vérifier les chemins absolus importés.
Il ne faut pas lancer le profil quotidien dans les deux modes en pensant qu’ils sont synchronisés.

Les paramètres utilisent l’API Java Preferences avec un backend fichier isolé et observable (thème),
contrôle de contenu SHA-256, fichier staging forcé puis remplacement atomique sans repli non atomique.
Un verrou exclut une deuxième instance de **ce profil portable**. Chemin vide/relatif/inaccessible,
corruption ou système de fichiers incompatible : échec, **jamais repli vers le profil installé**.
Les chemins par défaut refusent une sortie du dossier data, y compris un lien résolu vers l’extérieur.

### Tests et limites

Huit tests unitaires : activation explicite, confinement, deux profils isolés/relecture des valeurs,
corruption, concurrence, observabilité, altération à longueur identique et échec de publication sans
perte de la dernière version valide. Le smoke test de processus utilise la vraie variable d’environnement
et le provider de réglages, vérifie les chemins et la création du fichier de paramètres dans data.

La CI exécute ce smoke test dans un processus JVM configuré explicitement, sans ouverture de l’UI.
Les clés USB FAT/exFAT/NTFS, le lancement par double-clic du CMD, le retrait du support et les ACL
réelles restent à tester sous Windows 10. Si le support ne permet pas le remplacement atomique,
l’échec est volontairement explicite. Les fichiers staging échoués ne sont pas supprimés automatiquement.

Ce n’est pas un bac à sable : caches Java/OS, dialogues de fichiers, composants natifs et chemins
personnalisés peuvent être extérieurs. Les chemins explicitement choisis/importés ne sont pas
réécrits à l’insu de l’utilisateur. Ne pas retirer une clé avant fermeture et éjection sûre.
Voir `boitiertube-portable.md`, livré dans le ZIP.

## 4. Français

Les 19 textes repérés dans les réglages JVM des runtimes/économie de données/tray sont désormais des
ressources avec versions anglaise et française. Les états VLC/FFmpeg sont traduits, les noms techniques
et chemins Program Files, VLC/libVLC, FFmpeg, PATH, winget, MP3 restent intacts.

**403 clés** par défaut et **403 françaises**, XML sans doublons et paramètres de format vérifiés.
La recherche de littéraux `title`, `summary`, `Text`, `label` et descriptions d’accessibilité dans les
écrans desktop, la couche JVM et l’entrée desktop ne retrouve pas de libellé anglais correspondant
aux anciens réglages ; les formats techniques comme `MP3 • … kbps` ne sont pas traduits.
Cela ne remplace pas une relecture visuelle de chaque message dynamique ou erreur de bibliothèque.

## 5. Tests réellement exécutés

### Locaux

- Contrats d’identité : **13 tests réussis**.
- Contrats de l’audit étendu : **9 tests réussis**.
- Vérification des sept pointes historiques et des fichiers restés inchangés : **réussie**.
- Analyse syntaxique Kotlin des fichiers modifiés/ajoutés : **réussie**, ne remplace pas compilation.
- `git diff --check` : **réussi**.
- Gradle local réellement tenté : **échec avant démarrage, Java/JAVA_HOME absents**.
- PowerShell non disponible localement : contrôles dynamiques exécutés par la CI Windows.

Le script d’intégration ne prétend plus que les fichiers évolués sont identiques à l’octet : journal,
provider portable, résolution de chemins, entrée desktop et traduction JVM ont volontairement changé.
La preuve historique est conservée, et les nouveaux contrats/tests remplacent la comparaison brute
sur ces seuls fichiers. Les originaux restent atteignables dans Git.

### Windows Actions

Le pipeline contrôle maintenant les XML **archivés avant les smoke tests** et exige zéro échec/erreur/
ignoré dans les suites critiques. Cela évite de confondre les rapports d’un seul smoke test avec ceux
de toute la suite, ou un simple retour sans test avec une validation complète.

Exécutions intermédiaires :
- 44751662213 : obtention initiale du checksum VLC officiel, sans exécuter le binaire dans ce job.
- 44752419290 et 44752702552 : suites JVM, compilation et smoke tests des premiers profils/runtimes
  observés réussis ; ce ne sont pas les artefacts finaux de toutes les corrections.
- 44753734860 : échec du contrôle de sources après ajout du titre portable non ASCII ; lectures UTF-8
  explicites et annotations d’erreur ajoutées dans `dfb8ead44c`.

**Validation finale : [run 44754319938](https://github.com/Boitier13/NewPipe/actions/runs/44754319938),
code `dfb8ead44cfda7651176fb712a947e2d881a4db5` : SUCCÈS.** Job principal terminé en **14 min 42 s**.
Le job de contrôle de provenance figée a également réussi.

Les annotations issues de la lecture des XML par la CI confirment :

| Suite critique | Tests exécutés | Échecs | Erreurs | Ignorés |
| --- | ---: | ---: | ---: | ---: |
| `DesktopBackupStoreTest` | 19 | 0 | 0 | 0 |
| `DesktopRecoveryJournalTest` | 4 | 0 | 0 | 0 |
| `PortableProfileTest` | 8 | 0 | 0 | 0 |
| `DesktopDownloaderCookieTest` | 4 | 0 | 0 | 0 |

Ce sont **35 tests critiques attestés**, pas le total de toute la suite. Le smoke test natif
VLC/FFmpeg et le smoke test du processus portable sont ensuite exécutés séparément avec leur
vraie configuration d’environnement ; leurs étapes ont réussi. Les rapports complets de la suite
sont conservés avant ces réexécutions ciblées.

Étapes confirmées réussies : contrôles statiques et PowerShell (y compris archives négatives),
téléchargement avec SHA-256 épinglés et contrôles de structure, tests JVM, contrôles desktop,
build/distribution, initialisation native VLC/FFmpeg, profil portable explicite, MSI/EXE, contrôle
MSI, archive ZIP avec lanceur et publication des trois artefacts.
Avertissement non bloquant de sauvegarde du cache Gradle par `tar.exe` : pas un échec de test/build.

### Artefacts finaux confirmés par l’API GitHub

| Artefact | Contenu | Taille de l’archive Actions | Lien |
| --- | --- | ---: | --- |
| `boitiertube-windows` | Installateurs MSI et EXE | 633 565 056 octets | [Télécharger](https://github.com/Boitier13/NewPipe/actions/runs/44754319938/artifacts/14638495500) |
| `boitiertube-windows-portable` | ZIP complet, lanceur CMD, documentation | 364 990 731 octets | [Télécharger](https://github.com/Boitier13/NewPipe/actions/runs/44754319938/artifacts/14638730672) |
| `boitiertube-test-reports` | Rapports JVM complets et smoke tests/desktop | 53 169 octets | [Consulter](https://github.com/Boitier13/NewPipe/actions/runs/44754319938/artifacts/14636934759) |

Artefacts non expirés au contrôle. Les tailles sont celles des archives Actions, pas de chaque MSI
ou EXE. La conservation des distributions complètes augmente la taille par rapport aux anciens
artefacts. Aucun binaire ajouté à Git ; les artefacts n’ont pas été installés ou lancés dans le bac
à sable Linux. Leur rétention Actions n’est pas une release GitHub permanente.

Empreintes des **archives Actions** publiées par GitHub (distinctes des archives runtimes épinglées) :
- installateurs : `sha256:8dfe655b0ca43c07f227fa43a733ac1bff6d7f4b5fd77e98870e6e70e0f3d685` ;
- portable : `sha256:b9568a8ce9188e4daee1a96989b41c37c17b082f0cf98895cff0a5136c561b7d` ;
- rapports : `sha256:c0fe23a43e09c4b85a28a47c578d5da0ac26f1c8adba9b729be71a462a04c1f8`.

Le commit de bilan ultérieur ne modifie que la documentation ; code/workflow livrés identiques au
SHA testé ci-dessus. Les anciens commits centraux sont toujours ancêtres, le dépôt est propre après
publication. `dev` et les autres refs sont restées inchangées.

## 6. Points bloquants ou réserves avant diffusion publique

1. Finaliser le dossier de sources/licences de redistribution des binaires et dépendances.
2. Définir et tester la migration du nœud racine Java du mode installé ; pas de migration implicite.
3. Outiller la récupération explicite sur copie après interruption ; le journal bloque mais ne répare pas.
4. Recette Windows 10 réelle : MSI/EXE installation et mise à niveau, ZIP/CMD, supports USB, lecture,
   téléchargement long, annulation, import/export sur profils jetables et erreurs d’accès.
5. Tests d’arrêt forcé de processus / coupure matérielle supplémentaires ; signature et version de release.

Les versions sont figées et les archives vérifiées, mais cela ne constitue pas une certification
sécurité globale. Les essais Windows 10 doivent commencer sur des profils vierges, en conservant
les sauvegardes des données réelles indépendamment du dossier testé.
