package edu.must.campuslostfound;

import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AccountAuthenticationProvider implements AuthenticationProvider {
    private final AccountService accounts;

    AccountAuthenticationProvider(AccountService accounts) { this.accounts = accounts; }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName() == null ? "" : authentication.getName();
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();
        var account = accounts.authenticate(username, password).orElseThrow(() -> new BadCredentialsException("The credentials could not be verified."));
        // The stored role is loaded here. The submitted form cannot choose or upgrade it.
        return UsernamePasswordAuthenticationToken.authenticated(account.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
