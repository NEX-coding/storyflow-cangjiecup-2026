package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final StoryflowProperties properties;
    private final String username;

    public MailService(JavaMailSender mailSender,
                       StoryflowProperties properties,
                       @Value("${spring.mail.username:}") String username) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.username = username == null ? "" : username.trim();
    }

    public void sendRegisterCode(String to, String code) {
        ensureConfigured();
        String from = resolveFrom();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("【StoryFlow】注册验证码");
        message.setText("""
                你好，

                你正在注册 StoryFlow 账号，验证码为：%s

                验证码 %d 分钟内有效，请勿泄露给他人。
                如非本人操作，请忽略本邮件。

                — StoryFlow
                """.formatted(code, properties.getMail().getCodeTtlMinutes()));
        try {
            mailSender.send(message);
            log.info("Register code mailed to {}", to);
        } catch (Exception ex) {
            log.error("Failed to send register code to {}: {}", to, ex.getMessage());
            throw new BusinessException("验证码发送失败，请稍后重试");
        }
    }

    public boolean isConfigured() {
        return StringUtils.hasText(username) && StringUtils.hasText(resolveFrom());
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BusinessException("邮件服务未配置，请联系管理员");
        }
    }

    private String resolveFrom() {
        String from = properties.getMail().getFrom();
        if (StringUtils.hasText(from)) {
            return from.trim();
        }
        return username;
    }
}
