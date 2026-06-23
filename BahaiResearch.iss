; BahaiResearch Inno Setup Script
; Run package-installer.bat first to produce dist\installer\BahaiResearch\
; Then open this file in Inno Setup Compiler (or right-click -> Compile).

#define MyAppName "BahaiResearch"
#define MyAppVersion "1.3.2"
#define MyAppExeName "BahaiResearch.exe"
#define MyAppSourceDir "dist\installer\BahaiResearch"

[Setup]
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=BahaiResearch
AppComments=Baha'i scripture research tool
DefaultDirName={localappdata}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=dist\installer-output
OutputBaseFilename=BahaiResearch-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "{#MyAppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"

[InstallDelete]
; Clean up old JAR files so only the new one remains
Type: files; Name: "{app}\app\*.jar"
; Clean up the SQLite database so the new version regenerates it with new sources
Type: files; Name: "{app}\app\data\corpus\corpus.db"

[UninstallDelete]
Type: filesandordirs; Name: "{app}\app\data\corpus"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent
