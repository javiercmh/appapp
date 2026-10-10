package com.wynh.appapp.ai.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest

class IdTokenValidator(private val now: () -> Long = System::currentTimeMillis) {
    fun validate(token: String, clientId: String, nonce: String?, keys: JWKSet): Identity {
        try {
            val jwt = SignedJWT.parse(token)
            require(jwt.header.algorithm == JWSAlgorithm.RS256)
            require(jwt.header.criticalParams.isNullOrEmpty())
            val key = keys.getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: error("Missing key")
            require(key.keyUse == null || key.keyUse == KeyUse.SIGNATURE)
            require(key.algorithm == null || key.algorithm == JWSAlgorithm.RS256)
            require(jwt.verify(RSASSAVerifier(key.toRSAPublicKey())))
            val claims = jwt.jwtClaimsSet
            require(claims.issuer == OpenAiAuth.ISSUER)
            require(clientId in claims.audience)
            val authorizedParty = claims.getStringClaim("azp")
            if (claims.audience.size > 1 || authorizedParty != null) require(authorizedParty == clientId)
            require(claims.expirationTime != null && claims.expirationTime.time > now())
            require(claims.issueTime != null && claims.issueTime.time <= now() + 60_000)
            claims.notBeforeTime?.let { require(it.time <= now() + 60_000) }
            if (nonce != null) require(MessageDigest.isEqual(nonce.toByteArray(), (claims.getStringClaim("nonce") ?: "").toByteArray()))
            val subject = claims.subject?.takeIf { it.isNotBlank() } ?: error("Missing subject")
            return Identity(claims.issuer, subject, claims.getStringClaim("email"))
        } catch (_: Exception) {
            throw AuthFailure("The ChatGPT identity token could not be verified. Please restart sign-in.")
        }
    }
}
