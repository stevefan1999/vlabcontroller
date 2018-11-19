/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2018 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.auth.impl.kerberos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashSet;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosKey;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.kerberos.KeyTab;

import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.apache.kerby.kerberos.kerb.client.KrbConfig;
import org.apache.kerby.kerberos.kerb.gss.impl.GssUtil;
import org.apache.kerby.kerberos.kerb.request.ApRequest;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.ap.ApReq;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncTgsRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.apache.kerby.kerberos.kerb.type.ticket.TicketFlags;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider;
import org.springframework.security.kerberos.authentication.KerberosServiceRequestToken;

public class KRBServiceAuthProvider extends KerberosServiceAuthenticationProvider {
	
	private String backendPrincipal;
	private KRBClientCacheRegistry ccacheReg;
	
	public KRBServiceAuthProvider(String backendPrincipal, KRBClientCacheRegistry ccacheReg) {
		this.backendPrincipal = backendPrincipal;
		this.ccacheReg = ccacheReg;
	}
	
	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		KerberosServiceRequestToken auth = (KerberosServiceRequestToken) super.authenticate(authentication);
		
		try {
			ApReq apReq = parseApReq(auth.getToken());
			Subject subject = getCurrentSubject();

			SgtTicket proxyTicket = obtainProxyServiceTicket(apReq, subject);
			SgtTicket backendTicket = obtainBackendServiceTicket(backendPrincipal, proxyTicket.getTicket(), subject);
			
			String ccachePath = ccacheReg.create(auth.getName());
			File ccache = new File(ccachePath);
			KrbClient krbClient = new KrbClient((KrbConfig) null);
			krbClient.storeTicket(proxyTicket, ccache);
			krbClient.storeTicket(backendTicket, ccache);
		} catch (Exception e) {
			throw new BadCredentialsException("Failed to create client ccache", e);
		}
	
		return auth;
	}
	
	/**
	 * Parse the AP_REQ structure from the SPNEGO token
	 */
	private ApReq parseApReq(byte[] spnegoToken) throws IOException {
		byte[] apReqHeader = {(byte) 0x1, (byte) 0};
		
		int offset = 0;
		while (offset < spnegoToken.length - 1) {
			if (spnegoToken[offset] == apReqHeader[0] && spnegoToken[offset + 1] == apReqHeader[1]) {
				offset += 2;
				break;
			}
			offset++;
		}
		
		ByteArrayOutputStream tokenMinusHeader = new ByteArrayOutputStream();
		tokenMinusHeader.write(spnegoToken, offset, spnegoToken.length - offset);
		
		ApReq apReq = new ApReq();
		apReq.decode(tokenMinusHeader.toByteArray());
		return apReq;
	}

	/**
	 * Obtain the current subject (containing credential information) that was
	 * generated during the initialization of the SunJaasKerberosTicketValidator
	 */
	private Subject getCurrentSubject() throws Exception {
		Field ticketValidatorField = KerberosServiceAuthenticationProvider.class.getDeclaredField("ticketValidator");
		ticketValidatorField.setAccessible(true);
		Object ticketValidator = ticketValidatorField.get(this);
		
		Field subjectField = ticketValidator.getClass().getDeclaredField("serviceSubject");
		subjectField.setAccessible(true);
		Subject subject = (Subject) subjectField.get(ticketValidator);
		
		return subject;
	}
	
	private KerberosTicket findServiceTGT(Subject subject) throws PrivilegedActionException {
		return Subject.doAs(subject, new PrivilegedExceptionAction<KerberosTicket>() {
			@Override
			public KerberosTicket run() throws Exception {
				for (Object cred: subject.getPrivateCredentials()) {
					if (cred instanceof KerberosTicket) {
						return (KerberosTicket) cred;
					}
				}
				return null;
			}
		});
	}
	
	private SgtTicket obtainProxyServiceTicket(ApReq req, Subject subject) throws Exception {
		Ticket ticket = req.getTicket();
		int encryptType = ticket.getEncryptedEncPart().getEType().getValue();

		EncryptionKey sessionKey = findEncryptionKey(subject, encryptType);
		ApRequest.validate(sessionKey, req, null, 5 * 60 * 1000);
		
		EncTgsRepPart repPart = new EncTgsRepPart();
		repPart.setSname(ticket.getSname());
		repPart.setSrealm(ticket.getRealm());
		repPart.setAuthTime(ticket.getEncPart().getAuthTime());
		repPart.setStartTime(ticket.getEncPart().getStartTime());
		repPart.setEndTime(ticket.getEncPart().getEndTime());
		repPart.setRenewTill(ticket.getEncPart().getRenewtill());
		repPart.setFlags(ticket.getEncPart().getFlags());
		repPart.setKey(sessionKey);
		
		PrincipalName clientPrincipal = new PrincipalName(ticket.getEncPart().getCname().getName());
		clientPrincipal.setRealm(ticket.getEncPart().getCrealm());
		
		SgtTicket sgtTicket = new SgtTicket(ticket, repPart);
		sgtTicket.setClientPrincipal(clientPrincipal);
		return sgtTicket;
	}
	
	private EncryptionKey findEncryptionKey(Subject subject, int encryptType) throws PrivilegedActionException {
		return Subject.doAs(subject, new PrivilegedExceptionAction<EncryptionKey>() {
			@Override
			public EncryptionKey run() throws Exception {
				Set<KerberosKey> keySet = new HashSet<>();
				for (Object cred: subject.getPrivateCredentials()) {
					if (cred instanceof KerberosKey) {
						keySet.add((KerberosKey) cred);
					} else if (cred instanceof KeyTab) {
						KeyTab kt = (KeyTab) cred;
						KerberosKey[] k = kt.getKeys(kt.getPrincipal());
						for (int i = 0; i < k.length; i++) keySet.add(k[i]);
					}
				}
				KerberosKey[] keys = keySet.toArray(new KerberosKey[0]);
				return GssUtil.getEncryptionKey(keys, encryptType);    		
			}
		});
	}
	
	@SuppressWarnings("restriction")
	private SgtTicket obtainBackendServiceTicket(String backendServiceName, Ticket proxyServiceTicket, Subject subject) throws Exception {
		// Get client's ST that was submitted inside the SPNEGO token
		sun.security.krb5.internal.Ticket sunTicket = new sun.security.krb5.internal.Ticket(proxyServiceTicket.encode());

		// Get our own TGT that will be used to make the S4U2Proxy request
		KerberosTicket serviceTGT = findServiceTGT(subject);
		sun.security.krb5.Credentials serviceTGTCreds = sun.security.jgss.krb5.Krb5Util.ticketToCreds(serviceTGT);

		// Make a S4U2Proxy request to get a backend ST
		sun.security.krb5.KrbTgsReq req = new sun.security.krb5.KrbTgsReq(
				serviceTGTCreds,
				sunTicket,
				new sun.security.krb5.PrincipalName(backendServiceName));
		sun.security.krb5.Credentials creds = req.sendAndGetCreds();

		// Convert the reply from Sun to Kerby format
		EncTgsRepPart rep = new EncTgsRepPart();
		rep.setSname(new PrincipalName(backendServiceName));
		rep.setSrealm(proxyServiceTicket.getRealm());
		rep.setAuthTime(new KerberosTime(creds.getAuthTime().getTime()));
		rep.setStartTime(new KerberosTime(creds.getStartTime().getTime()));
		rep.setEndTime(new KerberosTime(creds.getEndTime().getTime()));
		rep.setStartTime(new KerberosTime(creds.getAuthTime().getTime()));
		if (creds.getRenewTill() != null) rep.setRenewTill(new KerberosTime(creds.getRenewTill().getTime()));

		int flags = 0;
		boolean[] flagArray = creds.getFlags();
		for (int i = 0; i < flagArray.length; ++i) {
			flags = (flags << 1) + (flagArray[i] ? 1 : 0);
		}
		rep.setFlags(new TicketFlags(flags));
		
		EncryptionKey sessionKey = new EncryptionKey();
		sessionKey.decode(creds.getSessionKey().asn1Encode());
		rep.setKey(sessionKey);
		
		Ticket backendServiceTicket = new Ticket();
		backendServiceTicket.decode(creds.getEncoded());
		
		PrincipalName clientPrincipal = new PrincipalName(creds.getClient().getName());
		clientPrincipal.setRealm(creds.getClient().getRealmAsString());
		
		SgtTicket sgtTicket = new SgtTicket(backendServiceTicket, rep);
		sgtTicket.setClientPrincipal(clientPrincipal);
		
		return sgtTicket;
	}
}