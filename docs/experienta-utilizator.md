# FileGrid PPC — Descrierea completă a experienței și a fluxurilor aplicației

## Scopul documentului

Acest document descrie **ce este FileGrid, cum îl folosește o persoană, ce se întâmplă în aplicație, cum arată interfața, cum se comportă agenții și ce comunicări sunt transmise în situații de urgență**.

Documentul este destinat unui AI care va proiecta și implementa produsul. El trebuie să păstreze comportamentul și experiența descrise aici, dar are libertate să decidă singur arhitectura, tehnologiile și modul tehnic de implementare.

Nu sunt prescrise framework-uri, limbaje de programare, baze de date, servicii, API-uri sau alte variante tehnice.

---

# 1. Ce este FileGrid

FileGrid este o platformă destinată activității unui operator de distribuție a energiei electrice. Aplicația centralizează primirea, organizarea, regăsirea și urmărirea fișierelor operaționale.

Utilizatorul nu trebuie să știe în ce sistem fizic ajunge un fișier și nici să îl caute manual în mai multe locuri. El lucrează într-o singură interfață: încarcă sau caută un fișier, vede cum este preluat și direcționat, iar apoi îl poate regăsi oricând în categoria sa.

FileGrid gestionează patru categorii principale:

1. **Citiri contoare inteligente** — date transmise automat de contoare; în demonstrație sunt generate de un simulator.
2. **Înregistrări de avarie** — fișiere încărcate de personalul din teren sau de dispecerat.
3. **Documente de racordare** — documente încărcate de angajații care procesează racordările și care pot conține date personale.
4. **Video-uri de inspecție cu drona** — fișiere video mari încărcate de echipele care inspectează liniile și echipamentele.

Cele patru categorii sunt reprezentate vizual prin patru case într-un spațiu numit **FileGrid Village**. Casele sunt modalitatea vizuală de organizare a datelor. Ele nu reprezintă locul fizic în care sunt păstrate fișierele.

---

# 2. Utilizatorii aplicației

FileGrid deservește mai multe tipuri de utilizatori:

- **Personalul din teren** încarcă înregistrări de avarie, fotografii, documente și video-uri de inspecție.
- **Dispecerul** caută rapid înregistrări de avarie, verifică informațiile asociate și urmărește situațiile care pot afecta rețeaua.
- **Angajatul care procesează racordări** încarcă, caută și accesează documentele aferente clienților.
- **Operatorul sau analistul** urmărește citirile contoarelor și informațiile agregate despre datele primite.
- **Managerul tehnic / responsabilul de incident** primește notificări când apare o situație critică și urmărește confirmarea și rezolvarea acesteia.
- **Administratorul** urmărește activitatea platformei, politicile de organizare și starea serviciilor.

Interfața trebuie să fie ușor de înțeles chiar și pentru un utilizator care nu cunoaște detaliile tehnice ale sistemelor de stocare.

---

# 3. Ecranul principal — FileGrid Village

## 3.1 Conceptul vizual

Ecranul principal este construit ca un mic sat digital, văzut dintr-o perspectivă izometrică sau ușor de sus. În sat se află:

- patru case distincte, fiecare reprezentând o categorie de date;
- un personaj central — **Agent 01**, responsabil de preluarea și rutarea fișierelor;
- trei servere vizibile în partea opusă a satului, reprezentând cele trei destinații de stocare: FTP, S3 și Database;
- cabluri sau trasee luminoase care conectează casele cu serverele;
- o zonă de activitate recentă și un rezumat al fișierelor procesate.

Cele patru case trebuie diferențiate prin simbol, denumire și aspect vizual:

| Casă | Categorie | Simbol vizual sugerat |
|---|---|---|
| Casa contoarelor | Citiri contoare inteligente | Contor electric / energie |
| Casa avariilor | Înregistrări de avarie | Fulger / avertizare |
| Casa documentelor | Documente de racordare | Dosar / document |
| Casa dronelor | Video-uri de inspecție | Dronă / cameră video |

Cele trei servere trebuie să fie ușor de recunoscut și să aibă etichete vizibile: **FTP**, **S3** și **Database**.

## 3.2 Cum se desfășoară animația

Când un fișier intră în FileGrid, utilizatorul trebuie să poată urmări vizual întregul traseu:

1. Fișierul apare în zona de intrare, iar Agent 01 se deplasează către el.
2. Agentul preia fișierul și afișează că îl analizează.
3. Agentul identifică tipul de date și se deplasează către casa categoriei corespunzătoare.
4. La sosirea în dreptul casei, categoria fișierului este confirmată vizual.
5. Agentul consultă decizia de rutare, iar pe ecran este evidențiat serverul ales.
6. Un pachet luminos de date pornește din zona casei și călătorește pe cablul corespunzător către server.
7. Serverul luminează sau își schimbă starea când primește fișierul.
8. După încheierea operației, utilizatorul vede confirmarea și poate deschide fișierul în categoria sa.

Animația trebuie să comunice clar că fișierul trece printr-un proces de analiză, clasificare, direcționare și stocare. Ea nu trebuie să fie doar decorativă: starea vizuală trebuie să corespundă rezultatului real al operației.

## 3.3 Stările vizuale ale unui fișier

Pe parcursul animației, utilizatorul trebuie să poată distinge următoarele stări:

- **Primit** — fișierul a intrat în aplicație.
- **În analiză** — Agent 01 examinează fișierul și informațiile sale.
- **Categorie identificată** — fișierul a fost asociat uneia dintre cele patru case.
- **Destinație selectată** — a fost stabilit serverul către care va fi trimis.
- **În transfer** — pachetul de date se deplasează către server.
- **Stocat și verificat** — operația s-a încheiat cu succes.
- **În așteptare** — fișierul nu poate fi încă finalizat și așteaptă reluarea operației.
- **Problemă la transfer** — utilizatorul este informat că operația a întâmpinat o problemă.

Succesul nu trebuie afișat înainte ca stocarea și verificarea să fie încheiate.

## 3.4 Interacțiunea cu casele

Fiecare casă este un element interactiv. La selectarea ei, utilizatorul intră în categoria respectivă și vede toate fișierele asociate acelei categorii.

De exemplu, selectarea Casei dronelor deschide lista tuturor video-urilor de inspecție, indiferent de serverul pe care se află fiecare fișier.

Pe fiecare casă poate apărea un mic rezumat:
- numărul de fișiere;
- volumul total de date;
- activitatea recentă;
- eventuale avertizări sau probleme.

---

# 4. Cum intră utilizatorul în aplicație și lucrează cu fișierele

## 4.1 Încărcarea unui fișier

Drag and Drop pe omuletul din mijlocul ecranului

Fluxul este următorul:

1. Utilizatorul selectează fișierul de pe dispozitiv.
2. Confirmă încărcarea.
3. Agentul analizează și clasifică fișierul.
4. Utilizatorul vede casa în care a fost încadrat și serverul selectat.
5. La final primește confirmarea și poate deschide pașaportul fișierului.


---

# 5. Navigarea prin categorii și găsirea fișierelor

După ce utilizatorul intră într-o casă, vede pagina categoriei respective.

Această pagină conține:
- denumirea și descrierea categoriei;
- numărul de fișiere și volumul total;
- cele mai recente fișiere;
- câmp de căutare;
- filtre;
- lista fișierelor;
- buton de încărcare, unde categoria permite upload manual.

## 5.1 Lista de fișiere

Fiecare rând sau card de fișier trebuie să prezinte informațiile esențiale:
- numele fișierului;
- categoria;
- data și ora;
- dimensiunea;
- stația/zona/equipment-ul, dacă există;
- storage-ul în care se află;
- starea fișierului;
- starea verificării integrității.

Utilizatorul poate sorta și filtra după:
- perioadă;
- dimensiune;
- nume;
- stație sau zonă;
- tipul fișierului;
- storage;
- stare;
- integritate.

Categoriile trebuie să rămână independente vizual, dar căutarea generală din aplicație poate permite găsirea fișierelor din toate cele patru categorii.

## 5.2 Deschiderea unui fișier

La selectarea unui fișier, utilizatorul intră în pagina lui individuală, numită **File Passport**.

File Passport este pașaportul digital al fișierului: locul în care utilizatorul vede identitatea, contextul, traseul, stocarea, integritatea și istoricul fișierului.

---

# 6. File Passport — pașaportul digital al fișierului

File Passport trebuie să fie una dintre cele mai importante pagini ale aplicației. El răspunde la întrebarea: **„Ce este acest fișier, de unde vine, unde a ajuns, este intact și cine l-a accesat?”**

## 6.1 Identitatea fișierului

În partea de sus a paginii:
- numele fișierului;
- ID-ul unic;
- categoria;
- tipul fișierului;
- dimensiunea;
- data și ora primirii;
- sursa fișierului;
- persoana sau simulatorul care l-a trimis.

## 6.2 Contextul operațional

În funcție de categorie, utilizatorul vede:
- stația sau zona;
- echipamentul asociat;
- data și ora evenimentului;
- identificatorul contorului, pentru citiri;
- tipul evenimentului;
- descrierea introdusă la încărcare.

Câmpurile care nu se aplică unui anumit tip de fișier nu trebuie afișate ca informații goale.

## 6.3 Traseul fișierului

O secțiune vizuală arată traseul parcurs:
1. primit de FileGrid;
2. analizat de Agent 01;
3. încadrat într-o categorie;
4. evaluat pentru stocare;
5. trimis către serverul ales;
6. stocat;
7. verificat.

Utilizatorul vede și destinația fizică selectată: FTP, S3 sau Database. Trebuie să poată înțelege de ce a fost aleasă destinația respectivă și ce alternative au fost luate în calcul.

## 6.4 Integritatea fișierului

O secțiune clară indică dacă fișierul recuperat este identic cu cel primit.

Stări posibile:
- **Verificat — intact:** verificarea a reușit.
- **Neverificat:** nu există încă un rezultat de verificare la recuperare.
- **Neconcordanță:** conținutul recuperat diferă de cel original; fișierul trebuie tratat ca suspect și nu trebuie prezentat ca valid.

Utilizatorul trebuie să poată vedea amprenta digitală SHA-256 și data ultimei verificări.

## 6.5 Istoric și audit

File Passport păstrează o cronologie a evenimentelor:
- fișier primit;
- clasificare;
- stocare;
- verificare;
- accesare;
- descărcare;
- schimbarea stării;
- transfer reluat;
- fallback;
- incident de integritate.

Evenimentele trebuie să indice momentul și actorul responsabil, acolo unde informația este disponibilă.

## 6.6 Acțiuni disponibile

În funcție de permisiunile utilizatorului, pagina permite:
- previzualizarea fișierului, când tipul permite;
- descărcarea fișierului;
- verificarea integrității;
- copierea ID-ului fișierului;
- vizualizarea istoricului;
- revenirea la categoria din care face parte.

Documentele personale nu trebuie expuse utilizatorilor care nu au drept de acces.

---

# 7. Decision Explorer — explicarea deciziei Agentului 01

Este un drop-down dupa ce incarci fisierul si agentul incepe procesul de trasport.

Pentru fișierul selectat, afișează:
- categoria identificată;
- informațiile relevante extrase;
- caracteristicile fișierului care au contat în decizie;
- storage-ul selectat;
- motivele pentru care acesta a fost ales;
- celelalte storage-uri evaluate;
- motivele pentru care alternativele nu au fost selectate;
- dacă a existat un fallback;
- rezultatul final al operației.

Exemplu de explicație vizibilă utilizatorului:

> „Fișierul video a fost direcționat către S3 deoarece are dimensiune mare, transferul poate fi reluat, iar destinația este disponibilă și respectă politica pentru această categorie.”

Explicațiile trebuie să fie bazate pe criteriile care au fost efectiv folosite, nu pe justificări inventate după efectuarea operației.

---

# 8. Agenții FileGrid și prezența lor în interfață

FileGrid are doi agenți vizuali principali. Ei trebuie să fie personaje recognoscibile, cu roluri distincte și comportament consecvent. Nu sunt doar elemente decorative: fiecare reprezintă o funcție clară a platformei.

## 8.1 Agent 01 — File Routing Agent

### Rol

Agent 01 este responsabil de gestionarea fișierelor. Este personajul central din FileGrid Village și reprezintă procesul prin care fiecare fișier este primit, analizat, clasificat, direcționat și urmărit.

### Personalitate și aspect vizual

- personaj prietenos, de tip robot/curier digital;
- are un ecran sau un element vizual care indică starea curentă;
- poate purta un mic pachet sau un simbol de fișier;
- are mișcări fluide, scurte și ușor de urmărit;
- își schimbă expresia sau culoarea în funcție de starea operației.

### Comportament în interfață

- În repaus, se află în zona centrală a satului.
- Când intră un fișier, observă notificarea și se deplasează către acesta.
- În timpul analizei, afișează o animație de scanare.
- După identificarea categoriei, merge către casa corespunzătoare.
- După stabilirea destinației, indică serverul selectat.
- Urmărește vizual pachetul de date pe traseul către server.
- La succes, afișează o confirmare.
- La probleme, afișează o stare de atenționare și explică pe scurt ce s-a întâmplat.

### Mesaje scurte ale Agentului 01

Exemple de texte care pot apărea lângă personaj:
- „Am primit un fișier nou.”
- „Analizez conținutul…”
- „Am identificat categoria: Înregistrare de avarie.”
- „Verific destinațiile disponibile.”
- „Am selectat S3 pentru acest fișier.”
- „Transfer în desfășurare…”
- „Fișier stocat și verificat.”
- „Transferul a fost întrerupt. Îl voi relua.”
- „Nu există momentan o destinație disponibilă. Fișierul a rămas în așteptare.”

Mesajele trebuie să descrie starea reală a fișierului.

## 8.2 Agent 02 — Grid Intelligence Agent

### Rol

Agent 02 este responsabil de interpretarea datelor operaționale ale rețelei. El nu gestionează traseul de stocare al fișierelor, ci analizează informațiile disponibile în acestea și caută tipare, anomalii și posibile corelări între evenimente.

### Personalitate și aspect vizual

- tehnician digital, cu cască de protecție;
- ține o tabletă sau un dispozitiv de analiză;
- are aspect mai tehnic decât Agent 01;
- este asociat cu o hartă schematică a rețelei electrice;
- în jurul său pot apărea simboluri pentru contoare, stații, transformatoare, linii și alerte.

### Unde apare în aplicație

Agent 02 are propriul spațiu numit **Grid Intelligence**. Acesta este accesibil din meniul principal și poate fi deschis și dintr-un fișier sau incident relevant.

Acesta este mult sub zona de case si de server.

Ecranul Grid Intelligence conține:
- o hartă schematică a rețelei;
- zone și stații reprezentate vizual;
- indicatori pentru citiri de contoare;
- incidente și avarii;
- anomalii detectate;
- statistici și evoluții în timp;
- o zonă în care Agent 02 își prezintă constatările.

### Ce analizează

Agentul poate analiza:
- citiri de contoare și variații de consum;
- importul și exportul prosumatorilor, când datele sunt disponibile;
- citiri lipsă, repetate sau neobișnuite;
- înregistrări de avarie;
- frecvența incidentelor pe zone sau echipamente;
- evenimente apropiate în timp și spațiu;
- date de telemetrie sau senzori, dacă există în setul de date utilizat.

### Cum prezintă constatările

Agentul nu trebuie să afirme o cauză sigură doar pentru că două evenimente apar apropiate în timp.

Fiecare constatare trebuie să prezinte:
- ce a observat;
- ce date susțin observația;
- zona și perioada analizate;
- nivelul de încredere;
- ce verificare umană poate fi necesară.

Exemplu:

> „Am identificat o creștere neobișnuită a numărului de alerte în zona Stației X. În aceeași perioadă apar variații de tensiune în datele disponibile. Aceste evenimente pot fi corelate, dar relația trebuie verificată de personalul tehnic.”

### Animația Agentului 02

- În repaus, analizează harta sau consultă tableta.
- Când pornește o analiză, privește harta și apar marcaje în zonele relevante.
- Când identifică o anomalie, se deplasează sau indică zona respectivă.
- La selectarea unei anomalii, deschide explicația și dovezile.
- Când nu există suficiente date, comunică explicit acest lucru.

Toate datele sintetice folosite în demonstrație trebuie marcate vizibil ca date simulate.

## 8.3 AI Operations Assistant — asistentul conversațional

Pe lângă cei doi agenți vizuali, FileGrid conține un asistent conversațional care permite utilizatorilor să pună întrebări despre fișiere și informațiile operaționale disponibile.

Acesta nu trebuie prezentat ca un al treilea personaj care preia rolurile celorlalți doi. El este o interfață de dialog, accesibilă dintr-o secțiune proprie sau dintr-un panou lateral.

Exemple de întrebări:
- „Ce incidente au fost în zona X în ultimele 24 de ore?”
- „Arată-mi înregistrările de avarie asociate Stației X.”
- „Ce fișiere video au fost încărcate după ultima furtună?”
- „Au existat probleme de integritate în ultimele 7 zile?”
- „Ce anomalii au fost identificate în citirile contoarelor?”

Răspunsurile trebuie să se bazeze pe informațiile disponibile în FileGrid și să indice fișierele sau înregistrările care le susțin. Dacă informația nu există, asistentul trebuie să spună acest lucru.

Agentul sa fie Agentic Rag(sa stie si altceva in afara unor intrebari standard)

---

# 9. Security & Incident Center

Security & Incident Center este spațiul în care sunt centralizate evenimentele care necesită atenție sau acțiune.

Acesta poate afișa:
- acces neautorizat;
- încercări de acces direct la storage;
- fișiere cu probleme de integritate;
- storage indisponibil;
- transferuri eșuate sau blocate;
- incidente de rețea introduse de utilizatori sau primite din surse disponibile;
- situații urgente raportate de personalul din teren.

Fiecare incident are:
- identificator;
- tip;
- nivel de severitate;
- dată și oră;
- zonă/stație/echipament, dacă se cunoaște;
- descriere;
- fișiere și dovezi asociate;
- persoanele sau echipele care trebuie informate;
- responsabil;
- stare: nou, notificat, confirmat, în lucru, rezolvat sau închis.

FileGrid nu trebuie să pretindă că detectează automat un incendiu sau o avarie fizică dacă nu primește o informație relevantă de la un senzor, sistem conectat sau utilizator.

---

# 10. Notificările WhatsApp în caz de urgență

## 10.1 Scop

În cazul unui incident critic, FileGrid trebuie să informeze rapid persoanele responsabile, astfel încât situația să fie preluată și urmărită.

WhatsApp este un canal de notificare și coordonare suplimentar. Nu înlocuiește procedurile oficiale, dispeceratul sau canalele de urgență ale operatorului.

Notificările se trimit numai persoanelor autorizate și relevante pentru incident:
- managerul tehnic;
- dispeceratul;
- responsabilul de incident;
- echipa de teren aferentă zonei;
- alte persoane desemnate în funcție de tipul evenimentului.

În demonstrație, notificările pot fi prezentate ca mesaje simulate. Interfața trebuie să indice clar dacă mesajul este doar simulat, pregătit pentru trimitere sau transmis efectiv.

## 10.2 Cum pornește fluxul de notificare

1. FileGrid primește sau înregistrează un eveniment critic.
2. Evenimentul este afișat în Security & Incident Center.
3. Sunt stabilite severitatea, zona și tipul incidentului.
4. Este identificată lista persoanelor care trebuie informate.
5. FileGrid pregătește mesajul cu detaliile cunoscute și identificatorul incidentului.
6. Mesajul este trimis sau simulat, în funcție de modul de funcționare al demonstrației.
7. În centrul de incidente apare starea notificării.
8. Destinatarii confirmă că au preluat informarea.
9. Incidentul rămâne urmărit până la rezolvare.
10. La rezolvare se poate transmite o actualizare finală.

Mesajele trebuie să fie scurte, clare și orientate spre acțiune. Ele nu trebuie să inventeze cauza, numărul clienților afectați sau durata estimată dacă aceste informații nu sunt cunoscute.

## 10.3 Mesaj — avarie majoră în rețea

**Destinatari:** dispecerat, manager tehnic și echipa de teren responsabilă de zonă.

> **[FILEGRID — ALERTĂ CRITICĂ]**
>
> A fost înregistrată o avarie majoră.
>
> **Incident:** {{incident_id}}  
> **Zonă:** {{zone}}  
> **Stație / echipament:** {{asset}}  
> **Ora raportării:** {{timestamp}}  
> **Descriere:** {{description}}  
> **Fișiere asociate:** {{file_count}}
>
> Este necesară verificarea și preluarea incidentului de către echipa responsabilă.
>
> Confirmați primirea mesajului și preluarea situației.
>
> **FileGrid — Security & Incident Center**

## 10.4 Mesaj — incendiu raportat la o stație

Acest mesaj se folosește numai când incendiul a fost raportat de o sursă relevantă sau de personal. FileGrid nu trebuie să prezinte o suspiciune drept incendiu confirmat.

**Destinatari:** dispecerat, manager tehnic și echipa de intervenție desemnată.

> **[FILEGRID — URGENȚĂ]**
>
> A fost raportat un posibil incendiu la un obiectiv din rețea.
>
> **Incident:** {{incident_id}}  
> **Obiectiv:** {{station_or_asset}}  
> **Locație:** {{location}}  
> **Ora raportării:** {{timestamp}}  
> **Sursa informației:** {{source}}
>
> **Acțiune:** verificați alerta și urmați procedurile oficiale de intervenție. Nu vă deplasați către obiectiv decât în condițiile stabilite de procedurile de siguranță.
>
> Confirmați primirea mesajului.
>
> **FileGrid — Security & Incident Center**

Dacă informația este doar o suspiciune, titlul și corpul mesajului trebuie să păstreze explicit formularea „posibil incendiu” sau „semnalare de verificat”.

## 10.5 Mesaj — problemă de securitate cibernetică

**Destinatari:** responsabilul IT/Security, administratorul FileGrid și persoanele desemnate.

> **[FILEGRID — ALERTĂ DE SECURITATE]**
>
> A fost detectat un eveniment de securitate care necesită verificare.
>
> **Incident:** {{incident_id}}  
> **Tip eveniment:** {{event_type}}  
> **Resursă afectată:** {{resource}}  
> **Ora:** {{timestamp}}  
> **Detalii:** {{description}}
>
> Accesul sau operația asociată a fost semnalată pentru verificare.
>
> Vă rugăm să confirmați preluarea incidentului și să urmați procedura internă de răspuns.
>
> **FileGrid — Security & Incident Center**

Mesajul nu trebuie să includă parole, token-uri, chei, date personale sau alte informații sensibile.

## 10.6 Mesaj — integritate compromisă

**Destinatari:** administratorul platformei, responsabilul de date și echipa tehnică.

> **[FILEGRID — ALERTĂ DE INTEGRITATE]**
>
> Verificarea unui fișier a identificat o neconcordanță între conținutul primit și cel recuperat.
>
> **Incident:** {{incident_id}}  
> **Fișier:** {{file_name}}  
> **File ID:** {{file_id}}  
> **Categorie:** {{category}}  
> **Ora verificării:** {{timestamp}}
>
> Fișierul a fost marcat ca suspect și nu trebuie tratat ca o copie validă până la verificarea situației.
>
> Confirmați preluarea incidentului.
>
> **FileGrid — Security & Incident Center**

## 10.7 Mesaj — storage indisponibil

**Destinatari:** administratorul platformei și echipa tehnică responsabilă.

> **[FILEGRID — ALERTĂ OPERAȚIONALĂ]**
>
> Un serviciu de stocare nu este disponibil.
>
> **Incident:** {{incident_id}}  
> **Serviciu:** {{storage_name}}  
> **Ora detectării:** {{timestamp}}  
> **Operații afectate:** {{affected_operations}}  
> **Stare:** {{current_status}}
>
> FileGrid urmărește operațiile afectate și starea recuperării. Verificați centrul de incidente pentru detalii.
>
> Confirmați primirea alertei.
>
> **FileGrid — Security & Incident Center**

## 10.8 Confirmarea și escaladarea notificărilor

Pentru fiecare mesaj trimis, FileGrid păstrează:
- destinatarul;
- incidentul asociat;
- data și ora trimiterii;
- starea livrării, dacă este disponibilă;
- confirmarea de primire;
- persoana care a preluat incidentul;
- momentul preluării;
- istoricul actualizărilor.

Stări vizibile:
- **Pregătit** — mesajul a fost compus;
- **Simulat** — mesajul a fost afișat în demo, fără trimitere reală;
- **Trimis** — mesajul a fost transmis;
- **Livrat** — există confirmarea disponibilă de livrare;
- **Confirmat** — destinatarul a confirmat preluarea;
- **Escaladat** — nu a fost confirmat în intervalul stabilit;
- **Închis** — incidentul a fost rezolvat și închis.

Dacă un incident critic nu este confirmat în intervalul stabilit de responsabilul organizației, FileGrid trebuie să poată semnala lipsa confirmării și să informeze următorul responsabil desemnat. Nu se presupune automat că un mesaj citit înseamnă că incidentul a fost preluat.

## 10.9 Actualizare la rezolvarea incidentului

> **[FILEGRID — ACTUALIZARE INCIDENT]**
>
> Incidentul {{incident_id}} a fost actualizat.
>
> **Zonă / obiectiv:** {{location_or_asset}}  
> **Stare:** {{status}}  
> **Actualizare:** {{resolution_update}}  
> **Ora:** {{timestamp}}
>
> {{next_action_or_closure}}
>
> **FileGrid — Security & Incident Center**

---

# 11. Fluxuri complete de utilizare — exemple

## 11.1 Exemplu: echipa din teren încarcă o înregistrare de avarie

1. Membrul echipei deschide FileGrid.
2. Intră în Casa avariilor.
3. Încarcă înregistrarea și completează stația, zona, echipamentul și descrierea disponibile.
4. Agent 01 preia fișierul și începe analiza.
5. Agentul îl identifică drept înregistrare de avarie și se deplasează către Casa avariilor.
6. Este evidențiat serverul ales, iar pachetul de date este animat pe traseul către acesta.
7. FileGrid confirmă stocarea și verificarea.
8. Utilizatorul deschide File Passport și verifică datele, traseul și integritatea.
9. Înregistrarea devine disponibilă pentru căutare de către utilizatorii autorizați.
10. Dacă informațiile indică sau raportează o situație critică, aceasta poate fi înregistrată în Security & Incident Center și poate declanșa fluxul de notificare.

## 11.2 Exemplu: dispecerul investighează o zonă cu incidente

1. Dispecerul deschide Grid Intelligence.
2. Selectează zona sau stația urmărită.
3. Agent 02 analizează citirile și înregistrările de avarie disponibile.
4. Pe hartă apar incidentele și anomaliile relevante.
5. Dispecerul selectează o constatare pentru a vedea explicația și dovezile.
6. Din constatare poate deschide fișierele sursă și pașapoartele acestora.
7. Dacă există un incident care necesită notificare, dispecerul sau fluxul de alertare îl înregistrează în Security & Incident Center.
8. Persoanele desemnate primesc notificarea, confirmă preluarea și urmăresc actualizările.

## 11.3 Exemplu: încărcarea unui video mare de dronă

1. Utilizatorul intră în Casa dronelor.
2. Selectează videoclipul și completează locația/echipamentul inspectat.
3. Pornește încărcarea și vede progresul.
4. Agent 01 preia fișierul și îl încadrează în categoria video.
5. Interfața arată destinația selectată.
6. Pachetul de date parcurge traseul vizual către server.
7. Dacă transferul este întrerupt, interfața indică pauza și apoi reluarea.
8. După finalizare și verificare, utilizatorul primește confirmarea.
9. Utilizatorul deschide File Passport, verifică integritatea și descarcă videoclipul când are nevoie.

## 11.4 Exemplu: identificarea unei anomalii în citirile contoarelor

1. Simulatorul transmite citiri continuu.
2. Utilizatorul vede activitatea în Casa contoarelor.
3. Agent 02 analizează datele disponibile.
4. O variație neobișnuită este semnalată în Grid Intelligence.
5. Utilizatorul deschide constatarea și vede perioada, zona, datele relevante și nivelul de încredere.
6. Utilizatorul poate deschide citirile și File Passport pentru a verifica sursa.
7. Dacă anomalia este confirmată ca incident operațional de personalul responsabil, aceasta poate fi înregistrată și urmărită în Security & Incident Center.

---

# 12. Organizarea meniului principal

Meniul principal trebuie să ofere acces rapid la:

1. **Village** — ecranul animat principal și cele patru categorii.
2. **File Explorer** — căutare generală în toate fișierele.
3. **Decision Explorer** — explicațiile deciziilor de rutare.
4. **Grid Intelligence** — analiza datelor de rețea realizată de Agent 02.
5. **AI Assistant** — întrebări în limbaj natural despre datele disponibile.
6. **Security & Incident Center** — incidente, alerte, notificări și confirmări.
7. **Performance Overview** — starea generală și performanța operațiunilor.

Indiferent de pagina în care se află, utilizatorul trebuie să poată reveni ușor la Village și să poată deschide un fișier direct în File Passport.

---

# 13. Reguli de experiență și prezentare

- Interfața trebuie să fie modernă, clară și coerentă vizual.
- Animația din Village trebuie să fie fluidă, dar să nu ascundă informațiile importante.
- Fiecare stare trebuie să aibă o etichetă textuală; culoarea sau animația nu sunt singurele indicii.
- Utilizatorul trebuie să poată opri sau să ignore animația și să acceseze direct informațiile.
- Casele reprezintă categorii, iar serverele reprezintă destinații de stocare. Aceste concepte nu trebuie confundate.
- Din orice listă de fișiere trebuie să se poată ajunge la File Passport.
- Dintr-un incident trebuie să se poată deschide fișierele și dovezile asociate.
- Agent 01 se ocupă de fișiere și rutare; Agent 02 se ocupă de analiza datelor de rețea.
- AI Operations Assistant oferă dialog și explicații, dar nu înlocuiește rolurile celor doi agenți.
- Notificările de urgență trebuie să fie concise, clare, adresate persoanelor potrivite și să permită urmărirea confirmării.
- În toate ecranele trebuie diferențiate clar datele reale de cele simulate.
- FileGrid nu trebuie să declare o cauză certă pentru o anomalie doar pe baza unei corelații.

---

# 14. Imaginea de ansamblu

FileGrid trebuie să fie perceput ca un centru unic de control pentru fișierele operaționale:

- **FileGrid Village** face procesul de primire și rutare ușor de urmărit.
- **Agent 01** gestionează parcursul fiecărui fișier.
- **Cele patru case** organizează fișierele pe categorii.
- **Cele trei servere** reprezintă destinațiile de stocare.
- **File Explorer** permite găsirea fișierelor.
- **File Passport** oferă identitatea, traseul, integritatea și istoricul fiecărui fișier.
- **Decision Explorer** explică de ce a fost aleasă o anumită destinație.
- **Agent 02 / Grid Intelligence** analizează datele și semnalează anomalii sau posibile corelări.
- **AI Operations Assistant** răspunde la întrebări despre informațiile disponibile.
- **Security & Incident Center** centralizează evenimentele critice.
- **WhatsApp** informează persoanele desemnate și permite urmărirea confirmării și a preluării incidentului.

Scopul experienței este ca un utilizator să poată urmări simplu și transparent întregul ciclu al datelor: de la intrarea lor în FileGrid, la organizare și stocare, până la regăsire, verificare, analiză și, când este necesar, notificarea persoanelor responsabile.
