!include "LogicLib.nsh"

!macro customInstall
  CreateDirectory "$APPDATA\Microsoft\Windows\SendTo"
  CreateShortCut "$APPDATA\Microsoft\Windows\SendTo\LinkPhone.lnk" "$INSTDIR\LinkPhone.exe" "--send" "$INSTDIR\LinkPhone.exe" 0
!macroend

!macro customUnInstall
  ${IfNot} ${isUpdated}
    Delete "$APPDATA\Microsoft\Windows\SendTo\LinkPhone.lnk"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "LinkPhone"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run" "LinkPhone"
  ${EndIf}
!macroend
