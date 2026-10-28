package com.fan.fanaiagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@Service
public class AccountService implements ApplicationRunner {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final String dummyHash = passwords.encode("not-a-real-password");
    @Value("${platform.admin-account:}") private String adminAccount;
    @Value("${platform.admin-password:}") private String adminPassword;
    public AccountService(JdbcTemplate db) { this.db=db; }
    public Account get(long id) {
        var rows=db.query("SELECT * FROM app_user WHERE id=?", (r,n)-> new Account(
            r.getString("id"), r.getString("account"),r.getString("username"),r.getString("avatar_url"),
            (Integer)r.getObject("gender",Integer.class),r.getString("phone"),r.getString("email"),
            r.getString("role"),r.getBoolean("enabled"),r.getBoolean("memory_enabled")), id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效");
        return rows.getFirst();
    }
    public Account register(Register input) {
        if(input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码的UTF-8编码长度请控制在72字节内");
        try {
            Long id=db.queryForObject("INSERT INTO app_user(account,password_hash,username) VALUES(?,?,?) RETURNING id",
                Long.class,input.account(),passwords.encode(input.password()),input.username().trim());
            return get(id);
        } catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"账号已存在"); }
    }
    public Account login(Login input) {
        var rows=db.queryForList("SELECT id,password_hash,enabled FROM app_user WHERE account=?",input.account());
        String hash=rows.isEmpty()?dummyHash:(String)rows.getFirst().get("password_hash");
        boolean matches=passwords.matches(input.password(),hash);
        if(rows.isEmpty() || !matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号或密码不正确");
        if(!Boolean.TRUE.equals(rows.getFirst().get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号已停用");
        return get(((Number)rows.getFirst().get("id")).longValue());
    }
    public Account update(long id, Profile profile) {
        db.update("UPDATE app_user SET username=?,avatar_url=?,gender=?,phone=?,email=?,memory_enabled=? WHERE id=?",
            profile.username().trim(),profile.avatarUrl(),profile.gender(),profile.phone(),profile.email(),profile.memoryEnabled(),id);
        return get(id);
    }
    public List<Account> list() {
        return db.query("SELECT id FROM app_user ORDER BY id LIMIT 200",(r,n)->get(r.getLong(1)));
    }
    @Transactional
    public void enable(long caller,long target,boolean enabled) {
        if(caller==target && !enabled) throw new ResponseStatusException(HttpStatus.CONFLICT,"请保留当前管理员的登录能力");
        if(db.update("UPDATE app_user SET enabled=? WHERE id=?",enabled,target)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        if(!enabled) db.update("UPDATE platform_run SET cancel_requested=TRUE WHERE user_id=? AND status='RUNNING'",target);
    }
    @Override public void run(ApplicationArguments args) {
        if(adminAccount==null || adminAccount.isBlank()) return;
        if(!adminAccount.matches("[a-zA-Z0-9_]{4,18}") || adminPassword==null || adminPassword.length()<12 || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalStateException("初始化管理员需要有效账号及12到72位密码");
        db.update("INSERT INTO app_user(account,password_hash,username,role) VALUES(?,?,?,'ADMIN') ON CONFLICT(account) DO NOTHING",
            adminAccount,passwords.encode(adminPassword),"管理员");
    }
}
