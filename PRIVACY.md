# Privacy policy

Private DNS Quick Toggle (`com.vorsk.privatednstoggle`) collects no personal data and contains no analytics, ads or tracking.

## What stays on your device
- The DNS servers you add and the app's options are stored only on your device.
- Backups are written only to a file you choose, and read only from a file you pick.

## Network use
The app has the Internet and network state permissions for one reason: to show whether each saved server works. When you open the app, it:
- looks up the names of the servers you added, using your network's DNS servers;
- connects to each of those servers on port 853 (DNS-over-TLS) and sends one DNS query, for `example.com`.

It connects to no other servers. Like any connection, these reveal your IP address to the DNS servers you added and to your network's DNS servers.

## Permissions
- **Change secure system settings** (`WRITE_SECURE_SETTINGS`), granted by you through adb or Shizuku: to change the Private DNS setting.
- **Run at startup**: so that turning Private DNS back on at the time you chose also works after a restart.
- **Internet and network state**: for the server check described above.

## Other apps
Other apps can change Private DNS through this app only if you turn on "Allow other apps to change Private DNS" in its options.

## Contact
Questions about this policy: open an issue at https://github.com/lanrat/PrivateDNSAndroid/issues
