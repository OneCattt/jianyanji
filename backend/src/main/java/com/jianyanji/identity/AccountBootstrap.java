package com.jianyanji.identity;

import static com.jianyanji.common.J.*;

import com.jianyanji.common.*;
import java.util.Arrays;
import org.slf4j.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 显式初始化管理员或本地演示账号，正常服务启动不自动创建或扩大权限。 */
@Component
public class AccountBootstrap implements ApplicationRunner {
    /** 初始化和账号操作日志，不输出密码。 */
    private static final Logger LOG = LoggerFactory.getLogger(AccountBootstrap.class);
    /** 账号持久化。 */
    private final Db db;
    /** 系统操作审计。 */
    private final Access access;
    /** 运行环境，控制初始化模式及凭据来源。 */
    private final Environment env;

    public AccountBootstrap(Db db, Access access, Environment env) {
        this.db = db;
        this.access = access;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean bootstrap = env.getProperty("jianyanji.bootstrap-admin", Boolean.class, false),
                seed = env.getProperty("jianyanji.seed-demo", Boolean.class, false);
        if (!bootstrap && !seed) return;
        boolean production =
                env.getProperty("jianyanji.production", Boolean.class, false)
                        || "production".equals(env.getProperty("NODE_ENV"));
        if (seed && (production || !env.getProperty("jianyanji.dev-auth", Boolean.class, false)))
            throw new IllegalStateException("仅允许显式开启 DEV_AUTH 的本地环境创建演示账号");
        db.tx(
                () -> {
                    if (bootstrap) {
                        String username = env.getProperty("jianyanji.admin-username", ""),
                                password = env.getProperty("jianyanji.admin-password", "");
                        Input.require(
                                username.matches("[a-zA-Z][a-zA-Z0-9_-]{2,31}")
                                        && password.length() >= 12
                                        && password.length() <= 128,
                                "请配置合规的管理员账号及至少12位密码");
                        if (db.one(
                                        "SELECT id FROM staff WHERE enabled=1 AND is_deleted=0 AND JSON_CONTAINS(permissions,?) LIMIT 1",
                                        "\"staff:manage\"")
                                != null) throw BusinessException.conflict("已有启用的账号管理员，请通过后台管理账号");
                        create(
                                username,
                                "品牌管理员",
                                password,
                                Arrays.asList(Access.PERMISSIONS),
                                "staff.bootstrap",
                                "首次初始化品牌管理员");
                    }
                    if (seed) {
                        String[][] users = {
                            {"visitor", "新朋友", "D"},
                            {"store", "林间调肤店", "C"},
                            {"principal", "清欣调肤主理人", "B"}
                        };
                        for (String[] user : users)
                            if (db.one(
                                            "SELECT id FROM users WHERE openid=? AND is_deleted=0",
                                            "dev:" + user[0])
                                    == null)
                                db.insert(
                                        "INSERT INTO users(openid,nickname,identity) VALUES(?,?,?)",
                                        "dev:" + user[0],
                                        user[1],
                                        user[2]);
                        if (db.one("SELECT id FROM staff WHERE username='admin' AND is_deleted=0")
                                == null)
                            create(
                                    "admin",
                                    "品牌管理员",
                                    "Jianyanji-Local-2026!",
                                    Arrays.asList(Access.PERMISSIONS),
                                    "staff.seed",
                                    "显式创建本地演示管理员");
                        if (db.one(
                                        "SELECT id FROM staff WHERE username='operator' AND is_deleted=0")
                                == null)
                            create(
                                    "operator",
                                    "客服小清",
                                    "Jianyanji-Local-2026!",
                                    Arrays.asList("users:read"),
                                    "staff.seed",
                                    "显式创建本地演示客服");
                    }
                });
        LOG.info(bootstrap ? "品牌管理员初始化完成，请使用配置的账号登录" : "本地演示账号已就绪，不覆盖现有账号和权限");
    }

    private void create(
            String username,
            String name,
            String password,
            Object grants,
            String action,
            String reason) {
        long id =
                db.insert(
                        "INSERT INTO staff(username,display_name,password_hash,permissions) VALUES(?,?,?,?)",
                        username,
                        name,
                        Passwords.hash(password),
                        stringify(grants));
        access.auditSystem("system", 0, action, "staff", id, reason, "success", "bootstrap-admin");
    }
}
