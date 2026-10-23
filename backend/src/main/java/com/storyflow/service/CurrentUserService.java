package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.entity.User;
import com.storyflow.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CurrentUserService {

    private static final ThreadLocal<User> REQUEST_USER = new ThreadLocal<>();

    private final StoryflowProperties properties;
    private final UserRepository userRepository;

    public CurrentUserService(StoryflowProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    public void setRequestUser(User user) {
        REQUEST_USER.set(user);
    }

    public void clearRequestUser() {
        REQUEST_USER.remove();
    }

    public User getRequestUser() {
        User requestUser = REQUEST_USER.get();
        if (requestUser != null) {
            return requestUser;
        }
        return resolveGuestUser().orElse(null);
    }

    public Long getCurrentUserId() {
        User requestUser = REQUEST_USER.get();
        if (requestUser != null && requestUser.getId() != null) {
            return requestUser.getId();
        }
        return resolveGuestUser()
                .map(User::getId)
                .orElseThrow(() -> new BusinessException(401, "请先登录"));
    }

    public boolean isGuest() {
        User user = getRequestUser();
        return user != null && isGuestEmail(user.getEmail());
    }

    /** 已通过 Bearer token 登录的真实用户（非访客 fallback） */
    public boolean isAuthenticated() {
        User user = REQUEST_USER.get();
        return user != null && !isGuestEmail(user.getEmail());
    }

    public Optional<Long> getGuestUserId() {
        if (!properties.getAuth().isGuestEnabled()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(properties.getAuth().getGuestEmail())
                .filter(User::isEnabled)
                .map(User::getId);
    }

    public boolean isGuestEmail(String email) {
        if (email == null) {
            return false;
        }
        return email.trim().equalsIgnoreCase(properties.getAuth().getGuestEmail());
    }

    private Optional<User> resolveGuestUser() {
        if (!properties.getAuth().isGuestEnabled()) {
            return Optional.empty();
        }
        String email = properties.getAuth().getGuestEmail();
        return userRepository.findByEmail(email).filter(User::isEnabled);
    }
}
