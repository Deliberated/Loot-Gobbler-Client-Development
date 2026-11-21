# Built by `gradlew packageRpm` from the app folder of `gradlew packageLinux`; the macros app_version, app_image,
# desktop_file and icon_file come from build.gradle.
#
# Installs into /opt/LootGobbler with a menu entry. %caps gives the launcher the capabilities libpcap needs for
# capturing, at every install and update, so the app never has to ask for them. The app keeps its data in
# ~/.local/share/LootGobbler, since /opt is read-only for the user (see lootgobbler.Launcher).

# the app folder carries its own Java runtime: no debug package, no stripping, no automatic dependencies from its libs
%global debug_package %{nil}
%global __os_install_post %{nil}
%global __brp_check_rpaths %{nil}

Name:           lootgobbler
Version:        %{app_version}
Release:        1
Summary:        Loot Gobbler - your right hand for your RotMG adventures
License:        MIT
URL:            https://github.com/Deliberated/Loot-Gobbler-Client-Legacy
BuildArch:      x86_64
AutoReqProv:    no
Requires:       libpcap

%description
A read-only Realm of the Mad God companion: loot log, collection, characters, DPS logger, guild features and more.
It reads the game's network traffic and never sends, blocks or modifies game packets.

%install
mkdir -p %{buildroot}/opt
cp -a %{app_image} %{buildroot}/opt/LootGobbler
install -Dm644 %{desktop_file} %{buildroot}%{_datadir}/applications/lootgobbler.desktop
install -Dm644 %{icon_file} %{buildroot}%{_datadir}/icons/hicolor/256x256/apps/lootgobbler.png

%files
%dir /opt/LootGobbler
%dir /opt/LootGobbler/bin
%caps(cap_net_raw,cap_net_admin=eip) /opt/LootGobbler/bin/LootGobbler
/opt/LootGobbler/lib
/opt/LootGobbler/LICENSE
/opt/LootGobbler/THIRD_PARTY_LICENSE-RealmShark.md
/opt/LootGobbler/OFL-Jersey10.txt
/opt/LootGobbler/OFL-Jersey15.txt
%{_datadir}/applications/lootgobbler.desktop
%{_datadir}/icons/hicolor/256x256/apps/lootgobbler.png
