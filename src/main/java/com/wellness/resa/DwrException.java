package com.wellness.resa;

/** Erreur renvoyée par le protocole DWR (réponse anormale ou exception serveur). */
public class DwrException extends RuntimeException {
    public DwrException(String message) {
        super(message);
    }
}
