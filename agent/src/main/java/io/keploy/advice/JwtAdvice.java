package io.keploy.advice;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import net.bytebuddy.asm.Advice;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
 
public class JwtAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.Return Jws<Claims> jws) {
        // Decode the JWT token
        Claims claims = jws.getBody();
        
        // Extract the relevant claims and other information
        String username = claims.getSubject();
        String newClaimValue = "newValue";
        
        // Generate a new JWT token with updated claims and a new expiration date
        LocalDate now = LocalDate.now();
        LocalDate expirationDate = now.plusYears(1);
        Date expiration = Date.from(expirationDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        String newToken = Jwts.builder()
                .setSubject(username)
                .claim("newClaim", newClaimValue)
                .setExpiration(expiration)
                .compact();
        
        // Return the new JWT token
        throw new RuntimeException(newToken);
    }
}
