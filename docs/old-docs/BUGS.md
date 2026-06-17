####

## Bug nr 1
Kiedy whcodze po raz pierwszy i pyta mnie o dwustopniowa autoryzacje dostaje w odpowiedzi:
{success: false}

mimo ze autoryzacja w icloud-service przebigla pomyslnie:
icloud-service-1  | INFO:     172.31.0.3:43526 - "POST /auth/2fa HTTP/1.1" 200 OK

moze cos jest zle mapowane?

## Bug nr 2
Po przejsciu na dashboard glowny, to mimo takiej odpowiedzi z devices:

[
    {
        "id": "device-external-drive",
        "deviceType": "EXTERNAL_DRIVE",
        "status": "CONNECTED",
        "connected": true,
        "lastCheckedAt": "2026-04-08T06:47:32.535Z",
        "details": "{\"available\": true, \"path\": \"/mnt/external-drive\", \"free_bytes\": 59548852224}"
    },
    {
        "id": "device-iphone",
        "deviceType": "IPHONE",
        "status": "DISCONNECTED",
        "connected": false,
        "lastCheckedAt": "2026-04-01T08:45:32.331Z",
        "details": "{\"connected\": false, \"device_name\": null, \"udid\": null}"
    },
    {
        "id": "device-icloud",
        "deviceType": "ICLOUD",
        "status": "CONNECTED",
        "connected": true,
        "lastCheckedAt": "2026-04-01T08:45:22.291Z",
        "details": "[{session_id=1d98bddb-d7b2-446b-b2c1-8428d503cce9, apple_id=witold.drozdzowskir@gmail.com, active=false}, {session_id=6c65c545-f083-4a22-a7ad-9ec1fbe75159, apple_id=witold.drozdzowskir@gmail.com, active=true}, {session_id=5697c40f-0e6f-42d8-b99e-36ac39c2dd56, apple_id=witold.drozdzowskir@gmail.com, active=true}]"
    }
]

Pokazuje mi status unknown w polu external drive

## Bug nr 3
pokazuje mi external drive:
[CHECKING] Sprawdzam dysk zewnętrzny pod /mnt/external-drive...
[CONNECTED] Dysk dostępny pod /mnt/external-drive.

list-disks zwraca cos takiego:
wdrozdzowski@Yoga-Witold:~/projects/multi-cloud-synchronizer/scripts$ ./list-disks.sh
[{"name": "sdb", "path": "/dev/sdb", "size": "2G", "type": "disk", "mountpoint": "[SWAP]", "label": null, "vendor": "Msft", "model": "Virtual Disk"}, {"name": "sdc", "path": "/dev/sdc", "size": "256G", "type": "disk", "mountpoint": "/mnt/wslg/distro", "label": null, "vendor": "Msft", "model": "Virtual Disk"}, {"name": "sdd", "path": "/dev/sdd", "size": "465.8G", "type": "disk", "mountpoint": null, "label": null, "vendor": "Samsung", "model": "PSSD T7 Touch"}, {"name": "sdd1", "path": "/dev/sdd1", "size": "465.8G", "type": "part", "mountpoint": null, "label": null, "vendor": "", "model": ""}]

Chcialbym zeby na froncie byla mozliwosc wyboru jaki z nich jest mozliwy. (i klikniecia wybierz)
Dodatkowo, dysk powinien byc podmountowany pod odpowiednia lokalizacje (tak zeby aplikacja z dockera miala odpowiedni mapping)
Moze trzeba dodac dodatkowe skrypty sprawdzajace - i odpowiednio pokazac uzytkownikowi ocb?

Dodatkowo - widze ze sa strony setup oraz setup/sync - moze tam trzeba cos popodmieniac - te strony sa nieosiagalne z dashboardu- powinny byc (przy wyborze dysku)



## Bug nr 4
Tile iCloud (Z device status) czasami pokazuje bardzo duzo sesji i sie rozjezdza
iCloud
Connected
Last checked:
4/8/26, 4:49 PM
[{session_id=7f27453e-910d-42be-814a-61830b8026d8, apple_id=witold.drozdzowskir@gmail.com, active=true}, {session_id=a092c992-d32d-43a5-9d8e-bedd1debf736, apple_id=witold.drozdzowskir@gmail.com, active=true}]

ogranicz je

## Bug nr 5
Po podlaczaniu wszystkich 3 urzadzen powinna byc sekcja synchronizacji. 
Rozpocznij - czy jest potrzebna, ile bedzie trwac, aktualny status. 
Dodaj to. 
