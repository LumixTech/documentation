<!--
Lumix PR sablonu. Kucuk ve odakli PR'lar tercih edilir.
Her madde, "kusursuz yonetim" hedefinin denetim listesidir.
-->

## Amac
<!-- Bu PR neyi cozuyor? Hangi ihtiyac/problem? -->


## Degisiklik ozeti
<!-- Ne degisti, madde madde. -->
-

## Ilgili ClickUp task'i
<!-- Branch adindan: feature/CU-<id>-... -->
Refs: CU-

## Test
<!-- Nasil test edildi? Hangi senaryolar? Otomatik/manuel? -->
-

## ADR linki (varsa)
<!-- Mimari karar verildiyse ilgili ADR dokumanini baglayin. -->


---

### Kalite kontrol listesi (PR acmadan once isaretleyin)
- [ ] **Build basarili** (`scripts/build-check.sh` veya pre-push hook yesil)
- [ ] **Testler gecti**
- [ ] Degisiklikler **task ile uyumlu** (kapsam disi is yok)
- [ ] **Gereksiz yorum / olu kod / debug ciktisi yok** (console.log, println, TODO-without-task)
- [ ] **Conventional Commits**'e uygun commit mesajlari
- [ ] **SOLID / DRY / KISS** prensiplerine uygun (tekrar yok, tek sorumluluk, gereksiz karmasiklik yok)
- [ ] Kod **optimize** ve proje standardina uygun
- [ ] **Sir/anahtar sizmasi yok** (.env, token, key commit edilmedi)
- [ ] Dokumantasyon/ADR (gerekiyorsa) guncellendi

### Reviewer'lar icin
- [ ] Bu PR kucuk ve gozden gecirilebilir boyutta
- [ ] Iki onay (2 approve) tamamlandi
