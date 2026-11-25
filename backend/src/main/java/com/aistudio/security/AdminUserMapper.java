package com.aistudio.security;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AdminUserMapper {

    String COLUMNS = "id, login, password_hash, credential_version, role, enabled, password_changed_at, last_login_at,"
            + " version, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM admin_user WHERE login = #{login}")
    AdminUser findByLogin(String login);

    @Select("SELECT " + COLUMNS + " FROM admin_user WHERE id = #{id}")
    AdminUser findById(long id);

    @Select("SELECT COUNT(*) FROM admin_user")
    long countAll();

    @Select("SELECT COUNT(*) FROM admin_bootstrap")
    long countBootstrap();

    @Insert("""
            INSERT INTO admin_user (login, password_hash, credential_version, role, enabled, password_changed_at,
                                    version, created_at, updated_at)
            VALUES (#{login}, #{passwordHash}, 1, 'ADMIN', TRUE, #{now}, 0, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(NewAdminUser user);

    @Insert("INSERT INTO admin_bootstrap (id, admin_user_id, created_at) VALUES (1, #{adminUserId}, #{now})")
    void insertBootstrap(@Param("adminUserId") long adminUserId, @Param("now") Instant now);

    @Update("""
            UPDATE admin_user
               SET password_hash = #{passwordHash}, credential_version = credential_version + 1,
                   password_changed_at = #{now}, version = version + 1, updated_at = #{now}
             WHERE id = #{id}
            """)
    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash, @Param("now") Instant now);

    @Update("UPDATE admin_user SET last_login_at = #{now}, updated_at = #{now} WHERE id = #{id}")
    void updateLastLogin(@Param("id") long id, @Param("now") Instant now);

    /** INSERT 후 생성 키를 받기 위한 가변 파라미터 객체. */
    final class NewAdminUser {
        public Long id;
        public final String login;
        public final String passwordHash;
        public final Instant now;

        NewAdminUser(String login, String passwordHash, Instant now) {
            this.login = login;
            this.passwordHash = passwordHash;
            this.now = now;
        }
    }
}
