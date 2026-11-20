# BoitierTube — téléchargements et abonnements

## Où retrouver mes fichiers ?

Par défaut, ouvrez **Documents** dans l’Explorateur Windows, puis **BoitierTube** :

- **Vidéos** contient les téléchargements vidéo ;
- **Audios** contient les téléchargements audio.

BoitierTube demande à Windows l’emplacement réel de Documents. Si Windows l’a déplacé vers un autre
disque ou OneDrive, cette redirection est respectée. Les dossiers sont créés au besoin. Le nom de
votre compte n’est pas programmé dans l’application.

Cette règle vaut aussi en mode portable : vos médias restent sur le PC, indépendamment du dossier
extrait de l’application. La base, les paramètres et les journaux restent séparés dans `data/profiles/<identifiant de compte>`.
Depuis la correction de confidentialité, aucun ancien profil NewPipe ou ancien `data` plat n’est
repris automatiquement. Voir `PORTABLE.md` pour les profils et les précautions de partage.
Déplacer BoitierTube ne déplace pas vos médias. Aucun ancien fichier de `data/downloads` n’est déplacé,
supprimé ou écrasé par ce changement : vous pouvez toujours l’ouvrir manuellement dans l’Explorateur.

Dans **Paramètres → Téléchargements**, les dossiers choisis personnellement restent prioritaires.
Si un ancien chemin est déjà enregistré, choisissez explicitement les nouveaux dossiers souhaités.
Ne supprimez pas l’ancien dossier pour changer de destination. Le bouton de dossier d’une tâche
terminée ouvre son emplacement dans l’Explorateur. Si Documents ou un disque réseau est inaccessible,
un message signale le problème : l’application ne choisit pas discrètement un autre emplacement.

## Pourquoi une tâche attend-elle ?

La limite est activée par défaut : **une seule tâche à la fois**, audio ou vidéo, y compris son
assemblage ou sa conversion. Les autres portent la mention **En attente d’une place dans la file**.
C’est adapté aux PC anciens et aux connexions lentes. Vous pouvez annuler une tâche en attente.

Désactiver la limite dans les paramètres autorise **jusqu’à trois tâches au total**, pas trois audios
plus trois vidéos. Cela peut ralentir chaque tâche et solliciter fortement le disque/processeur.
Réactiver la limite ne coupe pas les tâches déjà en cours : les suivantes attendront une place.
L’ordre strict de départ des tâches n’est pas garanti lorsqu’elles sont admises en concurrence.
La file n’est pas conservée après fermeture ; l’application ne propose pas de pause/reprise persistante.
Le téléchargement par segments HTTP n’est pas une garantie de reprise après redémarrage.

Sur un Dell OptiPlex 780 avec disque mécanique, gardez d’abord une tâche à la fois. Les tests de la
file ne prouvent pas le débit ou la stabilité de deux vrais téléchargements réseau simultanés sur
votre connexion : cette combinaison reste à essayer séparément, si nécessaire.

## Puis-je connecter mon compte YouTube ?

**Non.** BoitierTube ne propose pas de connexion Google/YouTube et ne demande jamais votre mot de passe.
Les abonnements sont enregistrés localement. Ils ne sont pas synchronisés automatiquement avec votre
compte. Vous pouvez vous abonner à une chaîne depuis sa page dans BoitierTube.

## Importer mes abonnements avec Google Takeout

1. Dans votre navigateur, ouvrez **https://takeout.google.com**. Connectez-vous uniquement sur Google.
2. Désélectionnez les autres produits. Choisissez **YouTube et YouTube Music**, puis les données
   d’abonnements disponibles. Les intitulés Google peuvent évoluer.
3. Demandez l’export, attendez sa préparation et téléchargez l’archive fournie par Google.
4. Extrayez l’archive avec l’Explorateur. Recherchez **subscriptions.csv** dans les dossiers YouTube.
5. Dans **Abonnements → Guide d’importation**, cliquez sur **Choisir un fichier** et choisissez ce CSV.
6. Vérifiez les chaînes importées. Actualisez ensuite le flux si vous voulez leurs dernières vidéos.

Ne choisissez pas le ZIP complet, un fichier de vidéos ou un historique de visionnage.
L’import accepte le CSV standard d’abonnements (identifiant, adresse et titre de chaîne) et les
exports JSON d’abonnements NewPipe/BoitierTube (`subscriptions`, `service_id`, `url`, `name`). Une
sauvegarde ZIP complète s’importe depuis la restauration des sauvegardes, pas sur cette page.

Les chaînes déjà présentes sont mises à jour sans duplication de leur adresse. Le nombre affiché
compte les entrées traitées, pas seulement les nouvelles chaînes. Une erreur d’écriture annule
l’import en cours. Le format CSV est testé sur des exemples représentatifs ; votre export Takeout
personnel reste à vérifier. Le fichier choisi est lu localement, sans envoi à Google. L’actualisation
du flux contacte les services vidéo ; le flux actuel se limite aux 25 premières chaînes, ce n’est
pas une synchronisation complète d’un compte.

## Mesurer une première lecture lente (facultatif)

La cause de la lenteur observée n’est pas encore mesurée sur votre PC. N’installez pas de codecs
ou de DLL trouvés au hasard, et ne désactivez pas l’antivirus.

Pour activer un petit journal de durées le temps d’un essai :

1. Ouvrez l’invite de commandes Windows dans le dossier BoitierTube extrait.
2. Tapez :

```bat
set BOITIERTUBE_DIAGNOSTICS=1
call Start-BoitierTube-Portable.cmd
```

3. Ouvrez trois vidéos l’une après l’autre, sans cliquer rapidement pendant un démarrage.
4. Notez approximativement le délai ressenti pour chacune, puis quittez complètement BoitierTube.
5. Conservez `playback_startup.log` dans votre sous-dossier `data\profiles\<identifiant de compte>`. Il contient des durées par étape, sans titres, adresses
   vidéo ou cookies. Fermer cette invite de commandes désactive ce réglage pour les lancements normaux.

Les étapes séparent extraction YouTube, sélection des flux, détection et création du lecteur VLC,
puis demande VLC jusqu’à l’événement de lecture. Ce dernier temps n’est pas le temps complet depuis
le clic et peut être ambigu en cas de changements très rapides de vidéo. Les durées ne doivent pas
être additionnées aveuglément, certaines étapes étant incluses dans d’autres. La création native est
paresseuse puis réutilisée ; FFmpeg est destiné aux téléchargements/conversions, pas à la lecture
VLC habituelle. Aucune accélération sur votre PC n’est affirmée sans cette nouvelle mesure.

## Une deuxième tâche semble ralentir la première

La limite d’une tâche est volontaire et reste inchangée. Une tâche **en attente** ne télécharge pas
ses flux et ne lance pas FFmpeg. En revanche, ouvrir sa vidéo peut déclencher extraction et lecture
automatique : la lecture consomme elle aussi réseau, processeur et disque. Faites un essai en mettant
la lecture en pause, sans fermer l’application, puis comparez le débit de la première tâche.
Ce n’est pas la preuve que la lecture explique votre ralentissement : réseau YouTube, assemblage,
réencodage et analyse antivirus peuvent aussi intervenir. Aucun gain n’est garanti.

Dans le Gestionnaire des tâches Windows, observez CPU, mémoire, disque et réseau de BoitierTube et
`ffmpeg.exe`, avant et après l’ajout. Notez le message de la tâche : un assemblage ou encodage n’est
plus un transfert réseau. N’arrêtez pas brutalement ces processus et ne désactivez pas l’antivirus.

La progression de transfert est **indicative**, pas un temps restant. Si la taille est inconnue,
aucun pourcentage n’est affiché. En assemblage/conversion, la barre est animée et le message indique
l’étape (et son avancement lorsque disponible). La préparation finale peut durer même à la fin de
l’étape FFmpeg. « Terminé » n’apparaît qu’après succès et présence d’un fichier final non vide.
Les temporaires propres à la tâche sont nettoyés dans son bloc de fin ; après une coupure de courant
ou un arrêt forcé, des restes peuvent subsister. Aucun nettoyage global des anciens fichiers n’est
lancé pour éviter d’effacer des données qui ne sont pas à cette tâche.
