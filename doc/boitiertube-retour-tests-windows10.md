# BoitierTube — audit ciblé après premiers essais Windows 10

## Référence et périmètre

PC réel : Dell OptiPlex 780, Windows 10, 6 Go de RAM, disque mécanique.
Les validations utilisateur sont conservées comme telles : lancement portable, accueil/tendances,
navigation, historique, lecture et formats dont 720p, téléchargement 144p et MP3 96 kbit/s, interface
et À propos satisfaisants hors liens personnels. Elles concernent la version précédemment testée ;
la nouvelle révision nécessite un essai ciblé, pas une remise en cause générale de ces résultats.

Travail exclusivement sur `arena/1461b4cf-newpipe`, sans modification de `dev`, création/suppression
de branche, migration des réglages ou déplacement d’anciens médias. Au début de la session, les
fichiers correspondaient exactement au commit distant `b8d117004f` mais les métadonnées Git locales
pointaient sur l’ancienne base. Comparaison via index temporaire, réalignement de la référence et
de l’index **sans écraser les fichiers**, puis téléchargement de l’historique complet pour les preuves.

## 1. Première lecture : faits établis et hypothèses

### Faits du code

- `JVMYouTubeBackend.getVideoDetails` s’exécute sur `Dispatchers.IO`, utilise `StreamInfo.getInfo`
  lorsque le cache de cette vidéo ne contient pas déjà de résultat, puis sélectionne/mappe les flux.
- L’extracteur est configuré une fois ; les résultats disposent d’un cache. Certaines initialisations
  internes YouTube/JavaScript et connexions réseau peuvent être communes aux vidéos suivantes.
- VLC est découvert à la demande ; un `CallbackMediaPlayerComponent` vidéo et un composant audio
  distinct sont créés paresseusement puis conservés. La création native et les commandes passent
  par un exécuteur dédié, pas le thread de l’interface.
- Historique et position de reprise sont traités sur le scope IO avant la demande de lecture.
- Le tampon réseau est de 1 500 ms. Ni cette valeur, ni les formats/codecs, ni la sélection des flux,
  ni les composants de décodage n’ont été modifiés : ils fonctionnent dans les essais réels.
- FFmpeg n’est pas utilisé pour la lecture VLC normale : il intervient pour téléchargements,
  assemblages et conversions. Sa disponibilité vérifie les fichiers ; ce n’est pas une initialisation
  de transcodage lors du premier clic vidéo.
- La redétection manuelle de VLC depuis un réglage/bouton reste synchrone ; ce chemin n’est pas celui
  de la lecture automatique habituelle. Pas de diagnostic affirmant qu’il explique le premier délai.

### Non démontré sur le PC utilisateur

La part respective du disque/antivirus, réseau/DNS/TLS, YouTube et déchiffrement des signatures,
chargement des DLL/plugins VLC, décodage initial et éventuelle attente DB n’est pas mesurée.
L’amélioration des lectures suivantes est compatible avec ces coûts à froid, mais ne prouve pas
qu’un composant particulier ou le matériel est fautif. Aucun chiffre ni accélération physique
n’est inventé. La durée précise observée n’a pas été fournie.

### Intervention volontairement limitée

Ajout d’une instrumentation **désactivée par défaut**, activée par `BOITIERTUBE_DIAGNOSTICS=1` :
`extracteur_initialisation`, `youtube_extraction`, `selection_flux`, `vlc_detection`,
`vlc_creation_video/audio`, `vlc_demande_vers_lecture`. Journal `data/playback_startup.log` en mode
portable, rotation bornée via le mécanisme existant. Aucun titre, URL ou cookie ajouté à ce journal.
Les erreurs de journalisation n’interrompent pas la lecture.

Le temps demande VLC → événement de lecture inclut attente de l’exécuteur, initialisation et tampon,
mais pas tout le temps depuis le clic. Des événements anciens peuvent rendre ce relevé ambigu lors
de changements rapides : le protocole demande trois vidéos successives sans clics concurrents.
Les durées imbriquées ne s’additionnent pas. Les accès DB préalables ne sont pas chronométrés ici.

Aucun préchargement automatique d’un deuxième moteur et aucune hausse de RAM native imposés au PC.
La réutilisation existante est préservée. **Il s’agit d’une mesure pour établir le diagnostic, pas
d’une correction prouvée du délai initial.** Instructions dans `boitiertube-guide-quotidien.md`.

## 2. Destinations : correction prioritaire

### Cause confirmée

Les défauts `WindowsAppPaths.defaultVideoDownloadDir/defaultAudioDownloadDir` utilisaient
`data/downloads` en portable, conformément à l’ancienne décision de profil isolé. Ce n’était ni
un chemin lié au nom BOITIER_FIRST ni un défaut d’extraction. La demande utilisateur change ce contrat.

### Nouveau contrat

Dossier Documents obtenu via l’API Windows **SHGetKnownFolderPath / FOLDERID_Documents** (GUID documenté),
avec libération mémoire par `CoTaskMemFree`. JNA déjà apporté par VLC est réutilisé, sans nouvelle
bibliothèque. Pas de concaténation `USERPROFILE + Documents`, de nom d’utilisateur codé en dur ou de
lecture approximative du registre. La localisation/redirection de Documents, dont OneDrive si
configuré par Windows, est respectée.

Par défaut, en installé **et** en portable :

```text
<Documents déclaré par Windows>\BoitierTube\Vidéos
<Documents déclaré par Windows>\BoitierTube\Audios
```

Sur un profil non redirigé cela correspond normalement aux deux chemins demandés sous
`C:\Users\BOITIER_FIRST\Documents`, mais le chemin réel de ce PC n’a pas été interrogé à distance.
Les dossiers sont créés et vérifiés ; les accents sont conservés.

Les clés existantes `download_path` et `download_path_audio` restent prioritaires si elles contiennent
un choix personnalisé. Aucune migration qui devinerait l’intention de l’utilisateur : si un ancien
chemin y est enregistré, le modifier explicitement depuis Paramètres → Téléchargements.
Les nouvelles valeurs par défaut ne sont plus liées au dossier extrait. Base, réglages, journaux
et temporaires restent dans le profil interne, séparés des médias finaux.

Aucun ancien fichier n’est déplacé ou supprimé. Les fichiers déjà présents ne sont pas choisis comme
nouvelle sortie. Les tâches audio/vidéo réservent désormais leurs noms dès leur mise en file : deux
tâches de même titre ne peuvent plus choisir la même sortie avant sa création. La comparaison tient
compte de la casse Windows. Ce verrouillage est interne au gestionnaire, pas une garantie contre
un programme externe créant un fichier entre réservation et écriture.

La préparation des dossiers/chemins est déportée hors du thread de l’interface, utile notamment pour
Documents redirigé vers un disque/réseau lent. Une erreur de préparation est affichée sans arrêter
le lecteur ; aucun repli silencieux vers le dossier de l’application. Ouvrir un dossier personnalisé
existant ne force pas une résolution Documents inutile en amont.

## 3. Gestionnaire : attente volontaire et défauts corrigés

### Fonctionnement réel

`DEFAULT_LIMIT_QUEUE = true` : **une tâche active au total**, audio ou vidéo, jusqu’à la fin de
l’assemblage/transcodage. L’observation réelle est cohérente avec ce réglage volontaire, pas la preuve
d’un blocage MP3 contre MP4. Désactiver le réglage existant autorise jusqu’à **trois tâches**, sans
ajouter un nouveau système de configuration ni augmenter ce maximum.

Les transferts/FFmpeg restent hors UI sur le scope IO. L’annulation est disponible ; pas de pause/
reprise persistante ni de file sauvegardée après redémarrage. Les requêtes HTTP Range ne prouvent pas
une reprise après fermeture. Des options de réglages partagées (réessais/réseau facturé) ne constituent
pas une implémentation complète de ces fonctions dans le contrôleur desktop ; elles ne sont pas
vendues comme nouvelles capacités. Le nombre de tâches en attente n’a pas de plafond propre.

### Défauts confirmés corrigés

- Deux sémaphores indépendants (un et trois) pouvaient se cumuler si le réglage changeait pendant
  des tâches actives. Remplacement par **un compteur d’admission partagé** audio/vidéo.
- Réduire la limite ne tue pas le travail actif ; les admissions suivantes attendent le retour
  sous le nouveau seuil. Pas de dépassement d’un maximum global de trois.
- Annulation en attente ou pendant une tâche libère correctement l’admission.
- Noms de sorties réservés pour les tâches en attente ; références de jobs terminés retirées.
- Mention explicite « En attente d’une place dans la file » et explication française sur l’écran
  Téléchargements, avec recommandation d’une tâche pour un ancien PC.

L’ordre strict FIFO n’est pas garanti en concurrence. Les tests vérifient des tâches de coroutine
pilotées, **pas deux téléchargements multimédias réseau simultanés réels**. Aucun gain de débit ou
fonctionnement MP4+MP3 simultané sur le Dell n’est déclaré prouvé. Conserver une tâche par défaut.

## 4. Abonnements : implémentation et explications

Aucune intégration Google OAuth/connexion compte/mot de passe n’existe dans cette application.
L’approche locale sans compte est héritée de NewPipe ; le README précise déjà « no Google account
required ». Le projet ne récupère pas automatiquement les abonnements privés d’un compte.
L’ajout local depuis une chaîne et le stockage SQLite sont implémentés.

L’import n’était **pas un bouton factice** : sélecteur → `SubscriptionsViewModel` → gateway JVM →
CSV/JSON → SQLite → rafraîchissement du flux de chaînes. Formats : CSV standard Takeout à trois
colonnes, ou export JSON NewPipe/BoitierTube avec `subscriptions`, `service_id`, `url`, `name`.
Le ZIP Takeout complet et les fichiers d’historique/vidéos ne sont pas des entrées de cette rubrique.

Améliorations ciblées :
- explication sans compte visible même sur la page vide, et bouton **Guide d’importation** ;
- guide français défilant : navigateur Google Takeout, sélection YouTube/abonnements, extraction
  du ZIP puis choix `subscriptions.csv`, formats et absence de synchronisation ;
- lecture CSV avec BOM, CRLF, virgules, guillemets doublés et titres multilignes ;
- refus des guillemets non fermés et des fausses adresses YouTube hébergées sur un domaine étranger ;
- fichier borné à 20 Mio et UTF-8 strict ; format non reconnu signalé au lieu d’un succès « 0 » ambigu ;
- import SQLite transactionnel : une erreur d’écriture annule tout le lot, sans import partiel ;
- doublons par `(service_id, url)` mis à jour, pas dupliqués. Le compteur indique les entrées traitées.

Les tests utilisent des **exemples représentatifs**, pas le fichier privé de l’utilisateur et pas
un export récupéré en direct sur Google. Le CSV et un aller-retour JSON sont aussi exercés sur le
vrai gateway SQLite dans le processus portable de CI, avec erreur d’écriture injectée par trigger
pour vérifier le rollback. Un export Takeout réel personnel reste à essayer.

Le flux de vidéos actuel consulte les 25 premières chaînes, en parallèle, et requiert Internet ;
ce n’est pas une synchronisation Google exhaustive. Cette architecture n’a pas été refondue.

## 5. À propos et licences

Liens retirés de l’interface :
- entrée « BoitierTube Desktop » vers `https://github.com/Boitier13/NewPipe` dans À propos ;
- champ website du dialogue GPL de BoitierTube qui pointait vers le même dépôt.

Recherche dans `shared/src` et `desktopApp/src` : aucune référence `Boitier13` ou `BOITIER_FIRST`
restante. Il n’y avait pas de lien distinct vers le profil personnel à supprimer en plus de ces deux
emplacements. Les liens GitHub techniques de composants ne sont pas assimilés au compte personnel.

Conservés : attribution NewPipe/NewPipe e.V. et son lien de projet d’origine, copyrights, texte GPL,
lecture de la licence complète, bibliothèques open source, Source Sans 3 et OFL, Adobe, notices et
provenance VLC/FFmpeg. Les informations légales ne sont pas supprimées pour masquer des liens.
Les liens du dépôt dans les rapports de développement/Git ne sont pas des boutons de présentation UI.

## 6. Vérifications et limites

### Local

- Contrôles identité : 13 tests réussis.
- Contrats audit étendu : 11 tests réussis.
- XML, couverture française et formats de chaînes vérifiés par ces contrôles.
- Preuves d’intégration des sept pointes : réussies après récupération de l’historique complet.
  Échec initial dû à l’objet Git manquant d’un clone superficiel, pas à une perte d’intégration.
- `git diff --check` réussi.
- Gradle local réellement tenté : échec avant démarrage, Java/JAVA_HOME absents.

### CI Windows finale

Run **44773438297 : SUCCESS**, sur `e5738692efed065e5f7fda4d818f9854d1b10161` :
https://github.com/Boitier13/NewPipe/actions/runs/44773438297

Tests JVM, contrôles desktop, distribution, runtimes natifs, processus portable isolé, packaging
MSI/EXE, vérification MSI et archivage ZIP terminés avec succès. Les sept suites critiques totalisent
**43 tests, zéro échec, erreur ou test ignoré**, selon les annotations du job `136416807726` :
19 sauvegardes, 4 récupération, 8 profil portable, 4 cookies, 5 destinations/file, 2 CSV, 1 timings.
Le smoke portable étendu (vrai SQLite, CSV/JSON et rollback) est passé dans son processus séparé.
Il ne faut pas confondre ces 43 tests critiques avec le total de toutes les suites JVM.

Avertissement non bloquant en fin de job : sauvegarde d’un cache impossible (`tar.exe`, code 2).
Ce n’est pas un échec des tests ni du packaging et aucune configuration de sécurité n’a été contournée.

Artefacts publiés par GitHub (empreintes de l’enveloppe d’artefact, pas des exécutables internes) :

| Artefact | ID | Taille | SHA-256 |
|---|---|---:|---|
| boitiertube-windows-portable | 14649966341 | 365037833 octets | `1e236bd35ba3dc02dcbfcb2ad5e88092de76a0124a39e08af0ee184cbfdfe5a0` |
| boitiertube-windows | 14648962244 | 633657750 octets | `4ccd9ff82336948d5534aa02f3503358b531c037da01961f3304846b467709a7` |
| boitiertube-test-reports | 14649971417 | 55730 octets | `ff8b460c15428f06fb8aa30679c393b723f880b6ece0fad95d26ce97a19e35b2` |

La récupération locale des rapports par `gh run download` a été tentée mais échoue sur l’hôte Azure
avec EOF (restriction réseau du sandbox). Les résultats ci-dessus reposent sur le statut des étapes
GitHub et les annotations contrôlant les XML, pas sur une inspection locale indépendante du ZIP.
Aucune publication publique/release n’a été faite. Le dernier commit de bilan est documentaire
uniquement ; le ZIP validé contient bien le guide quotidien mais pas ce bilan ultérieur.

Nouveaux tests validés : `PostWindowsDownloadTest` (5), `PostWindowsSubscriptionsTest` (2),
`PlaybackStartupTimingTest` (1), plus scénario du vrai gateway dans `PortableModeSmokeTest`.
Le pipeline compare l’API native Documents à `[Environment]::GetFolderPath('MyDocuments')`,
contrôle les suites critiques via leurs XML et conserve les résultats complets avant smoke tests.
Les tests d’écriture MP3/MP4 des destinations écrivent des **sentinelles**, pas de vrais médias.
Ils prouvent la création/écriture des dossiers accentués, pas un transfert YouTube en direct.

### Réessai physique nécessaire

1. Extraire le nouveau ZIP indépendamment, utiliser le lanceur portable et vérifier son titre.
2. Sur un profil sans destination personnalisée, télécharger une courte vidéo 144p et un MP3 96 kbit/s.
   Vérifier Documents → BoitierTube → Vidéos/Audios et l’ouverture dans l’Explorateur. Les anciens
   fichiers de l’autre dossier doivent rester intacts. Vérifier ensuite un dossier personnalisé.
3. Avec la limite activée, lancer les deux tâches : la deuxième doit afficher son attente puis partir.
   Tester l’annulation d’une attente. La concurrence réseau réelle reste facultative et distincte.
4. Refaire trois premières lectures successives, éventuellement avec journal de diagnostic ; ne pas
   attribuer la lenteur au PC sans les durées. Revalider 720p, les formats et l’historique sur cette révision.
5. Ouvrir le guide, importer un CSV Takeout personnel extrait, vérifier chaînes et absence de doublons.
6. Vérifier À propos/Licences : absence de lien personnel et maintien des attributions.

La CI Windows Server n’est pas le PC Windows 10 du client ; le lancement UI/CMD, OneDrive/Documents
redirigé réel, lecture/débit, antivirus et ergonomie nécessitent cet essai. Les tests automatisés ne
certifient pas l’absence de toute régression. Runtimes épinglés et sauvegardes sécurisées restent
soumis aux réserves juridiques et de récupération manuelle déjà documentées.

## 7. Commits

- `aa47c1462` : Documents Windows, file partagée, import transactionnel, explications, liens personnels,
  instrumentation et premiers tests ciblés.
- `b7de8c04a` : protection des noms sans distinction de casse Windows et contrats statiques.
- `e17dea765` : préparation des téléchargements hors thread de l’interface.
- `63f03c629` : message d’erreur visible sans interrompre la lecture et ouverture des dossiers personnalisés.
- `e5738692e` : guide quotidien français livré dans le ZIP et documentation portable actualisée.

La pointe finale de bilan sera publiée sur la même branche, sans altérer `dev`.
