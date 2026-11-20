# BoitierTube — finalisation portable

## Décisions maintenues

Pas de connexion Google/OAuth, pas de nouvelle fonctionnalité importante, pas de refonte.
L’import local d’abonnements est la méthode retenue. Les formats, la lecture et la navigation
validés sur Windows 10 sont conservés. Travail sur `arena/1461b4cf-newpipe` uniquement, sans
modification de `dev` ni suppression de branche. Crédits, licences et attributions inchangés.

## Téléchargements et ralentissement signalé

La limite est volontaire : **une tâche active par défaut**, audio et vidéo confondus, jusqu’à
la fin de l’assemblage ou de la conversion. La désactivation du réglage permet déjà trois tâches
au maximum. Ce contrat n’est pas modifié. Aucune concurrence obligatoire, aucun pipeline parallèle
entre transfert et assemblage ajouté.

La file utilise une coroutine suspendue (`StateFlow.first`), pas une boucle de polling. Le contrôle
réseau, la création des temporaires et FFmpeg sont **dans** le bloc d’admission. Une deuxième tâche
en attente ne lance donc ni ses requêtes média ni son FFmpeg. Avant l’admission, seuls création/
vérification du dossier, réservation du nom et publication de la tâche sont effectués. Les mises
à jour de transfert et de FFmpeg sont déjà espacées d’environ 500 ms. Pas de cache média en RAM
pour toute une vidéo : copies tamponnées et fichiers temporaires sur disque.

Le test `FinalDownloadAuditTest` exerce le véritable contrôleur avec un serveur HTTP local : première
réponse tenue ouverte, deuxième tâche en attente, troisième annulée. Pendant cet intervalle, une
seule requête doit atteindre le serveur ; après libération, seule la deuxième doit partir, et les
octets des deux sorties doivent être identiques aux données servies. Ce test établit l’isolation
de la file ; il ne mesure pas le débit YouTube ni les ressources du Dell.

**La cause précise du ralentissement du premier téléchargement sur le PC réel n’est pas établie.**
L’ouverture d’une autre vidéo peut démarrer automatiquement sa lecture et son extraction, en dehors
de la file de téléchargement. Ces opérations utilisent réseau/CPU/mémoire/disque même si son
nouveau téléchargement attend. Autres hypothèses : variation du serveur/connexion, analyse antivirus,
passage du transfert à l’assemblage ou au réencodage, contention disque entre temporaires et sortie.
Un réencodage libx264 peut fortement utiliser le CPU ; le stream-copy lit et écrit surtout le disque.
Aucune mesure CPU/RAM/IO réelle sur Windows 10 n’est disponible dans ce sandbox Linux sans Java.
Pas d’augmentation arbitraire du nombre de tâches, des tampons, des threads ou de la mémoire.

Protocole conseillé : comparer le même transfert avant/après ajout d’une tâche, lecture mise en pause,
puis noter dans le Gestionnaire des tâches CPU, mémoire, disque et réseau de BoitierTube/ffmpeg.exe,
ainsi que l’heure et le message d’étape. Ne pas désactiver l’antivirus ni tuer FFmpeg. Le guide livré
dans le ZIP explique ce diagnostic. Une tâche à la fois reste le choix prudent sur HDD/6 Go de RAM.

## Corrections limitées

- Attente : barre vide statique, et non animation pouvant faire croire à un transfert.
- Transfert de taille inconnue : pas de faux total égal aux octets déjà reçus, pas de pourcentage.
- Assemblage/conversion : animation indéterminée et message d’étape existant (avec avancement d’étape
  quand disponible), plutôt qu’un pourcentage global pondéré donnant une fausse impression de blocage
  à 94–99 %. Le transfert connu affiche un pourcentage explicitement indicatif, plafonné à 99 avant
  le succès. Ces pondérations ne sont pas une estimation du temps restant.
- Un fichier final absent ou vide n’est plus déclaré « Terminé ». Le contrôle vérifie le chemin réel
  de sortie, y compris le repli WebM ; une validation codec complète n’est pas ajoutée.
- Réservations des noms WebM de secours libérées ; copie de secours sans écrasement d’un fichier
  existant. Les temporaires restent nettoyés dans `finally`, pas par purge générale des anciens fichiers.
- Erreurs résiduelles en anglais remplacées par des messages français dans recherche, chaîne,
  playlist, vidéo, VLC et échecs de téléchargement. Les détails techniques de téléchargement restent
  dans `download_mux.log`. Le libellé FLAC devient « Sans perte ». Noms des formats, bibliothèques,
  contenus des chaînes, textes de licences et diagnostics développeur ne sont pas traduits artificiellement.
- Provenance du ZIP : `VERSION-PORTABLE.txt` contient le commit et le run GitHub ; le pipeline rouvre
  le ZIP et contrôle ce commit, le lanceur, les deux guides et l’absence d’un profil `data` embarqué.

## Dossiers, données et temporaires

Le lanceur `Start-BoitierTube-Portable.cmd` déclare explicitement la racine portable, appelle
l’exécutable depuis son propre dossier et conserve son code de sortie. Aucun réglage installé n’est
copié. Le processus de test portable vérifie base, paramètres et journaux sous `data`, et Documents
hors de ce profil. Il ne prouve pas un double-clic interactif sur Windows 10.

Documents est résolu par l’API native Windows puis comparé en CI à la référence PowerShell :
`Documents\BoitierTube\Vidéos` et `Documents\BoitierTube\Audios`, y compris en portable. Les chemins
personnalisés restent prioritaires et doivent être absolus. Les anciens dossiers ne sont ni migrés
ni purgés. Les noms existants sont évités et les noms en attente sont réservés. Cette protection
intra-application n’est pas un verrou contre un logiciel externe créant le même fichier au même instant.

Les temporaires de tâche sont supprimés en fin normale/échec/annulation via `finally`. Un test du
contrôleur audio natif vérifie l’écriture dans un dossier personnalisé, la préservation d’un ancien
fichier et la disparition du nouveau temporaire. Les octets du test ne sont pas un vrai média.
Après coupure électrique ou arrêt forcé, `finally` peut ne pas être exécuté ; aucun nettoyage global
risquant d’effacer d’anciens fichiers n’est ajouté. L’annulation d’un appel réseau natif bloqué peut
attendre une lecture/un timeout ; pas de refonte des appels bloquants à ce stade.

## Abonnements et stabilité

Les tests existants couvrent CSV représentatif Takeout (BOM, CRLF, guillemets, titres multilignes),
CSV malformé/domaine trompeur, import réel SQLite, réimport sans doublon, aller-retour JSON et rollback
sur erreur d’écriture injectée. Le guide est accessible même sans abonnement, et livré en `GUIDE.md`.
ZIP Takeout brut non accepté dans cette rubrique ; choisir `subscriptions.csv` extrait. Pas de compte
Google ni synchronisation ajoutée. Les doublons sont comparés par `(service_id, url)`, pas par identité
YouTube normalisée entre deux adresses différentes. Un export personnel réel reste distinct des
exemples représentatifs. Le flux de chaînes conserve sa limite existante aux 25 premières chaînes.

Pas de suppression de fonctions prétendument inutilisées : la présence d’interfaces multiplateformes
ou de replis techniques ne suffit pas à prouver leur inutilité. Aucun `TODO()`/`NotImplementedError`
repéré dans les contrôleurs JVM et viewmodels desktop audités. Les réserves déjà documentées sur
récupération après coupure, tests USB/systèmes de fichiers et redistribution légale ne sont pas
transformées en garanties par la finalisation.

## Vérifications et livraison

Local : contrôles identité (13) et audit (11), couverture ressources françaises/XML, conservation
historique des sept pointes et `git diff --check`. La preuve historique a nécessité de récupérer
l’historique complet du clone. Gradle local tenté mais non exécuté : Java/JAVA_HOME absents.

La CI Windows associée au **commit final** est la preuve pour compilation/JVM, vérifications desktop,
runtimes VLC/FFmpeg natifs, processus portable, MSI/EXE et ZIP. Elle exige aussi l’exécution de trois
nouveaux tests d’intégration HTTP/temporaires en plus des 43 tests critiques précédents. Le bilan
remis dans la conversation donne le résultat réel, le SHA final et le lien de l’artefact exact ;
ce document ne préjuge pas du succès d’un run encore à venir au moment de son écriture.

Le dernier ZIP doit porter dans `VERSION-PORTABLE.txt` le même SHA que son run. Aucune release publique
n’est demandée. Après une CI complète réussie, aucune modification supplémentaire n’est nécessaire
pour la livraison portable ; le ralentissement observé reste une limite à diagnostiquer, pas une
accélération promise. Les tests Windows Server ne remplacent pas les essais physiques Windows 10
que l’utilisateur juge globalement concluants.
