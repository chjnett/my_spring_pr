package com.secureflow.secureflow.user;

import com.secureflow.secureflow.global.exception.DuplicateEmailException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UpdateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    //실제 앱 spring이 UserRepository를 UserService 생성자에 주입
    //단위 테스트 :Mockito가 가짜 Repository를 만들고 우리가 UserService 생성자에 직접 전달

    @Mock
    private UserRepository userRepository;

    private UserService userService;
    @BeforeEach
    void setUp(){
        userService=new UserService(userRepository);


    }
    //테스트들

    @Test
    void createsUserWhenEmailIsAvailable() {

        CreateUserRequest request = new CreateUserRequest("Hyeonjun", "hyeonjun@example.com");
        User savedUser = new User(request.name(), request.email());
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);

        UserResponse response = userService.createUser(request);

        assertThat(response.name()).isEqualTo("Hyeonjun");
        assertThat(response.email()).isEqualTo("hyeonjun@example.com");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo(request.email());
    }

    @Test
    void rejectsDuplicateEmailBeforeSave() {

        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(new CreateUserRequest("Hyeonjun", "taken@example.com")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void translatesDatabaseUniqueViolationToDuplicateEmail() {
        CreateUserRequest request = new CreateUserRequest("Hyeonjun", "race@example.com");
        SQLException postgresException = new SQLException(
                "duplicate key value violates unique constraint \"uk_user_email\"",
                "23505"
        );
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email", postgresException));

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining(request.email());
    }

    @Test
    void doesNotTranslateUnrelatedIntegrityViolationToDuplicateEmail() {
        CreateUserRequest request = new CreateUserRequest("Hyeonjun", "error@example.com");
        SQLException postgresException = new SQLException("null value violates not-null constraint", "23502");
        DataIntegrityViolationException integrityViolation =
                new DataIntegrityViolationException("invalid user", postgresException);
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(integrityViolation);

        assertThatThrownBy(() -> userService.createUser(request))
                .isSameAs(integrityViolation);
    }

    @Test
    void rejectsLookupOfMissingUser() {

        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser(404L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void changesNameWithoutExplicitSave() {
        User user = new User("before", "user@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse response = userService.modifyUser(1L, new UpdateUserRequest("after"));

        assertThat(response.name()).isEqualTo("after");
        assertThat(user.getName()).isEqualTo("after");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsUpdateOfMissingUser() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.modifyUser(404L, new UpdateUserRequest("after")))
                .isInstanceOf(UserNotFoundException.class);
    }
}
