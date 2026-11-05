# Scopri semplificato — Lyra Android 1.0.11

Versione 1.0.11, codice 49. Compilazione e firma restano locali.

Menu principale: Playlist Automatica, Mix per me, Novità. Il secondo menu contiene
solo Pop, Rock, House, Techno. La ricerca avanzata offre autocompletamento tipizzato
per artista e album; gli album vengono riprodotti integralmente e in ordine.
Mix per me chiede al server il profilo `for_me`, che usa gradimento, skip, ascolti
completi e preferiti, con fallback al motore locale. Novità verifica l'anno
dell'edizione YTM corrente, scartando le
ristampe riconoscibili; non certifica l'anno originale delle singole registrazioni.

Il server invia lotti da 20 ID e il rifornimento parte con cinque brani futuri in
coda. Nessun vecchio filtro di
classificazione consultato nelle radio locali. In caso di indisponibilità server,
anche durante il rifornimento di una sessione verificata, Lyra può continuare con
la radio locale dichiarando la minore affidabilità del genere.

L'indirizzo server e l'email di supporto provengono dal file privato esterno
`signing.properties`; il repository pubblico contiene soltanto valori di esempio.
