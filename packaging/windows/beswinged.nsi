; NSIS-Installer für beswinged. Wird von dist.sh aufgerufen:
;   makensis /DVERSION=1.1.0 /DAPPDIR=<jpackage-App-Ordner> /DLICENSEFILE=<Liesmich+Lizenz.txt>
;            /DICON=<beswinged.ico> /DOUTFILE=<Setup.exe> beswinged.nsi
; Installiert nach "Programme\beswinged", legt Startmenü-Eintrag, optional Desktop-Verknüpfung,
; den Eintrag unter "Installierte Apps" (Registry) und einen Deinstaller an.

Unicode true
SetCompressor /SOLID lzma
ManifestDPIAware true

!include "MUI2.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!define APPNAME "beswinged"
!define PUBLISHER "xycle1smurf"
!define URL "https://github.com/xycle1smurf/beswinged"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"
!define APPPATHKEY "Software\Microsoft\Windows\CurrentVersion\App Paths\${APPNAME}.exe"

Name "${APPNAME} ${VERSION}"
OutFile "${OUTFILE}"
InstallDir "$PROGRAMFILES64\${APPNAME}"
InstallDirRegKey HKLM "Software\${APPNAME}" "InstallDir"
; TESTUSER (nur für Tests): ohne Adminrechte, ohne Registry und Verknüpfungen, ohne Willkommensseite
!ifdef TESTUSER
  RequestExecutionLevel user
!else
  RequestExecutionLevel admin
!endif
BrandingText "${APPNAME} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1031 "ProductName" "${APPNAME}"
VIAddVersionKey /LANG=1031 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "FileDescription" "${APPNAME} Setup"
VIAddVersionKey /LANG=1031 "CompanyName" "${PUBLISHER}"
VIAddVersionKey /LANG=1031 "LegalCopyright" "GNU GPL v3"

; ------------------------------------------------------------------ Oberfläche

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING

!define MUI_WELCOMEPAGE_TEXT "Dieser Assistent installiert ${APPNAME} ${VERSION}, einen Viewer für große JSON-Lines-Logdateien.$\r$\n$\r$\nDie benötigte Java-Laufzeit ist enthalten.$\r$\n$\r$\n$_CLICK"
!ifndef TESTUSER
  !insertmacro MUI_PAGE_WELCOME
!endif

; Liesmich und GPL auf einer Seite, muss akzeptiert werden
!define MUI_PAGE_HEADER_TEXT "Liesmich und Lizenz"
!define MUI_PAGE_HEADER_SUBTEXT "Bitte lesen Sie die Hinweise und die Lizenzbedingungen (GNU GPL v3)."
!define MUI_LICENSEPAGE_TEXT_TOP "Zuerst die Hinweise (Liesmich), darunter die Lizenz."
!define MUI_LICENSEPAGE_CHECKBOX
!define MUI_LICENSEPAGE_CHECKBOX_TEXT "Ich habe die Hinweise gelesen und akzeptiere die Lizenz"
!insertmacro MUI_PAGE_LICENSE "${LICENSEFILE}"

!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_TEXT "${APPNAME} ist installiert und im Startmenü zu finden.$\r$\n$\r$\nTipp: Nach dem Start mit Rechtsklick auf das Symbol in der Taskleiste $\"An Taskleiste anheften$\" wählen."
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APPNAME}.exe"
!define MUI_FINISHPAGE_RUN_TEXT "${APPNAME} jetzt starten"
!define MUI_FINISHPAGE_RUN_NOTCHECKED
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "German"

; ------------------------------------------------------------------ Installation

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "${APPNAME} benötigt ein 64-Bit-Windows."
    Abort
  ${EndIf}
  SetRegView 64
FunctionEnd

Section "${APPNAME} (erforderlich)" SecMain
  SectionIn RO
  SetShellVarContext all
  ; Reste einer früheren Version entfernen (Einstellungen liegen im Benutzerordner und bleiben)
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  SetOutPath "$INSTDIR"
  File /r "${APPDIR}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
!ifndef TESTUSER

  ; Startmenü
  CreateShortCut "$SMPROGRAMS\${APPNAME}.lnk" "$INSTDIR\${APPNAME}.exe" "" "$INSTDIR\${APPNAME}.exe" 0 SW_SHOWNORMAL "" "JSON-Lines-Log-Viewer"

  ; Installierte Apps / Programme und Features
  WriteRegStr HKLM "Software\${APPNAME}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINSTKEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\${APPNAME}.exe,0"
  WriteRegStr HKLM "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTKEY}" "URLInfoAbout" "${URL}"
  WriteRegStr HKLM "${UNINSTKEY}" "HelpLink" "${URL}"
  WriteRegStr HKLM "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${UNINSTKEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKLM "${UNINSTKEY}" "EstimatedSize" "$0"

  ; Start über Win+R / Eingabeaufforderung mit "beswinged"
  WriteRegStr HKLM "${APPPATHKEY}" "" "$INSTDIR\${APPNAME}.exe"
  WriteRegStr HKLM "${APPPATHKEY}" "Path" "$INSTDIR"
!endif
SectionEnd

Section /o "Desktop-Verknüpfung" SecDesktop
!ifndef TESTUSER
  SetShellVarContext all
  CreateShortCut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${APPNAME}.exe" "" "$INSTDIR\${APPNAME}.exe" 0
!endif
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "Programm mit Java-Laufzeit, Startmenü-Eintrag und Eintrag unter Installierte Apps."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "Verknüpfung auf dem Desktop aller Benutzer."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; ------------------------------------------------------------------ Deinstallation

Function un.onInit
  SetRegView 64
FunctionEnd

Section "Uninstall"
!ifndef TESTUSER
  SetShellVarContext all
  Delete "$SMPROGRAMS\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
!endif
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\${APPNAME}.exe"
  Delete "$INSTDIR\${APPNAME}.ico"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
!ifndef TESTUSER
  DeleteRegKey HKLM "${UNINSTKEY}"
  DeleteRegKey HKLM "${APPPATHKEY}"
  DeleteRegKey HKLM "Software\${APPNAME}"
!endif
SectionEnd
