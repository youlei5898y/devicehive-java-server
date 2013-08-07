package com.devicehive.service;

import com.devicehive.dao.NetworkDAO;
import com.devicehive.dao.UserDAO;
import com.devicehive.model.Network;
import com.devicehive.model.User;
import com.devicehive.model.UserRole;
import com.devicehive.model.UserStatus;
import com.devicehive.service.helpers.PasswordProcessor;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.container.ContainerRequestContext;
import java.util.List;
import java.util.Set;

/**
 * This class serves all requests to database from controller.
 */
@Stateless
@EJB(beanInterface = UserService.class, name = "UserService")
public class UserService {

    private static final int MAX_LOGIN_ATTEMPTS = 10;
    private static final long LAST_LOGIN_TIMEOUT = 60000;


    @Inject
    private PasswordProcessor passwordService;

    @Inject
    private UserDAO userDAO;

    @Inject
    private NetworkDAO networkDAO;

    @Inject
    private TimestampService timestampService;

    /**
     * Tries to authenticate with given credentials
     *
     * @param login
     * @param password
     * @return User object if authentication is successful or null if not
     */
    public User authenticate(String login, String password) {
        User user = userDAO.findActiveByName(login);
        if (user == null) {
            return null;
        }
        if (!passwordService.checkPassword(password, user.getPasswordSalt(), user.getPasswordHash())) {
            user.setLoginAttempts(user.getLoginAttempts() + 1);
            if (user.getLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                user.setStatus(UserStatus.LOCKED_OUT);
            }
            return null;
        } else {
            user.setLoginAttempts(0);
            if (System.currentTimeMillis() - user.getLastLogin().getTime() > LAST_LOGIN_TIMEOUT) {
                user.setLastLogin(timestampService.getTimestamp());
            }
            return user;
        }
    }

    /**
     * updates password for user
     *
     * @param id       user id
     * @param password password
     * @return User with updated parameters
     */
    public User updatePassword(@NotNull long id, String password) {

        User u = userDAO.findById(id);

        if (password != null) {
            String salt = passwordService.generateSalt();
            String hash = passwordService.hashPassword(password, salt);
            u.setPasswordHash(hash);
            u.setPasswordSalt(salt);
        }
        if (userDAO.update(id, u)) {
            return u;
        }
        return null;
    }

    /**
     * updates user, returns null in case of error (for example there is no such user)
     *
     * @param id       user id to update if null field is left unchanged
     * @param login    new login if null field is left unchanged
     * @param role     new role if null field is left unchanged
     * @param status   new status if null field is left unchanged
     * @param password new password if null field is left unchanged
     * @return updated user model
     */
    public User updateUser(@NotNull long id, String login, UserRole role, UserStatus status, String password) {

        User u = userDAO.findById(id);
        if (u == null) {
            throw new NotFoundException("User not found.");
        }
        if (login != null) {
            u.setLogin(login);
        }

        if (role != null) {
            u.setRole(role);
        }

        if (status != null) {
            u.setStatus(status);
        }

        if (password != null) {
            String salt = passwordService.generateSalt();
            String hash = passwordService.hashPassword(password, salt);
            u.setPasswordHash(hash);
            u.setPasswordSalt(salt);
        }

        if (userDAO.update(id, u)) {
            return u;
        }

        return null;
    }

    /**
     * Allows user access to given network
     *
     * @param userId    id of user
     * @param networkId id of network
     */
    public void assignNetwork(@NotNull long userId, @NotNull long networkId) {
        User existingUser = userDAO.findById(userId);
        if (existingUser == null) {
            throw new NotFoundException();
        }
        Network existingNetwork = networkDAO.getByIdWithUsers(networkId);
        if (existingNetwork == null) {
            throw new NotFoundException();
        }
        Set<User> usersSet = existingNetwork.getUsers();
        usersSet.add(existingUser);
        existingNetwork.setUsers(usersSet);
        networkDAO.merge(existingNetwork);
    }

    /**
     * Revokes user access to given network
     *
     * @param userId    id of user
     * @param networkId id of network
     */
    public void unassignNetwork(@NotNull long userId, @NotNull long networkId) {
        User existingUser = userDAO.findById(userId);
        if (existingUser == null) {
            throw new NotFoundException();
        }
        Network existingNetwork = networkDAO.getByIdWithUsers(networkId);
        if (existingNetwork != null) {
            existingNetwork.getUsers().remove(existingUser);
            networkDAO.merge(existingNetwork);
        }
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public List<User> getList(String login, String loginPattern, Integer role, Integer status, String sortField,
                              Boolean sortOrderAsc, Integer take, Integer skip) {
        return userDAO.getList(login, loginPattern, role, status, sortField, sortOrderAsc, take, skip);
    }

    /**
     * retrieves user by login
     *
     * @param login user login
     * @return User model, or null if there is no such user
     */
    public User findByLogin(String login) {
        return userDAO.findByLogin(login);
    }

    /**
     * Retrieves user by id (no networks fetched in this case)
     *
     * @param id user id
     * @return User model without networks, or null if there is no such user
     */
    public User findById(@NotNull long id) {
        return userDAO.findById(id);
    }

    /**
     * Retrieves user with networks by id, if there is no networks user hass access to
     * networks will be represented by empty set
     *
     * @param id user id
     * @return User model with networks, or null, if there is no such user
     */
    public User findUserWithNetworks(@NotNull long id) {
        return userDAO.findUserWithNetworks(id);
    }

    /**
     * Retrieves user with networks by id, if there is no networks user hass access to
     * networks will be represented by empty set
     *
     * @param login user login
     * @return User model with networks, or null, if there is no such user
     */
    public User findUserWithNetworksByLogin(@NotNull String login) {
        return userDAO.findUserWithNetworksByLogin(login);
    }

    /**
     * creates new user
     *
     * @param login    user login
     * @param role     user role
     * @param status   user status
     * @param password password
     * @return User model of newly created user
     */
    public User createUser(@NotNull String login, @NotNull UserRole role, @NotNull UserStatus status, @NotNull String password) {
        return userDAO.createUser(login, role, status, password);
    }

    /**
     * Deletes user by id. deletion is cascade
     *
     * @param id user id
     * @return true in case of success, false otherwise
     */
    public boolean deleteUser(long id) {
        return userDAO.delete(id);
    }

    /**
     * @param requestContext ContainerRequestContext from calling controller
     * @return user, currently logged on
     */
    public User getCurrent(ContainerRequestContext requestContext) {
        String login = requestContext.getSecurityContext().getUserPrincipal().getName();

        if (login == null) {
            return null;
        }

        return findUserWithNetworksByLogin(login);
    }


}
