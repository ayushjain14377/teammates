package teammates.ui.webapi.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidHttpRequestBodyException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.UnauthorizedAccessException;
import teammates.common.util.Const;
import teammates.ui.webapi.output.StudentsData;
import teammates.ui.webapi.request.StudentsEnrollRequest;

/**
 * Enroll a list of students.
 *
 * <p>Create the students who are not in the course.
 *
 * <p>Update the students who are already existed.
 *
 * <p>Return all students who are successfully enrolled.
 */
public class EnrollStudentsAction extends Action {

    private static final String ERROR_MESSAGE_SAME_TEAM_IN_DIFFERENT_SECTION =
            "Team \"%s\" is detected in both Section \"%s\" and Section \"%s\"."
                    + " Please use different team names in different sections";

    @Override
    protected AuthType getMinAuthLevel() {
        return authType.LOGGED_IN;
    }

    @Override
    public void checkSpecificAccessControl() {
        if (!userInfo.isInstructor) {
            throw new UnauthorizedAccessException("Instructor privilege is required to access this resource.");
        }
        String courseId = getRequestParamValue(Const.ParamsNames.COURSE_ID);

        InstructorAttributes instructor = logic.getInstructorForGoogleId(courseId, userInfo.id);
        gateKeeper.verifyAccessible(
                instructor, logic.getCourse(courseId), Const.ParamsNames.INSTRUCTOR_PERMISSION_MODIFY_STUDENT);
    }

    @Override
    public JsonResult execute() {

        String courseId = getNonNullRequestParamValue(Const.ParamsNames.COURSE_ID);
        StudentsEnrollRequest enrollRequests = getAndValidateRequestBody(StudentsEnrollRequest.class);
        List<StudentAttributes> studentsToEnroll = new ArrayList<>();
        enrollRequests.getStudentEnrollRequests().forEach(studentEnrollRequest -> {
            studentsToEnroll.add(StudentAttributes.builder(courseId, studentEnrollRequest.getEmail())
                    .withName(studentEnrollRequest.getName())
                    .withSectionName(studentEnrollRequest.getSection())
                    .withTeamName(studentEnrollRequest.getTeam())
                    .withComment(studentEnrollRequest.getComments())
                    .build());
        });

        List<StudentAttributes> existingStudents = logic.getStudentsForCourse(courseId);
        validateTeamName(existingStudents, studentsToEnroll);

        Set<String> existingStudentsEmail =
                existingStudents.stream().map(StudentAttributes::getEmail).collect(Collectors.toSet());
        List<StudentAttributes> enrolledStudents = new ArrayList<>();
        studentsToEnroll.forEach(student -> {
            if (existingStudentsEmail.contains(student.email)) {
                // The student has been enrolled in the course.
                StudentAttributes.UpdateOptions updateOptions =
                        StudentAttributes.updateOptionsBuilder(student.getCourse(), student.getEmail())
                                .withName(student.getName())
                                .withSectionName(student.getSection())
                                .withTeamName(student.getTeam())
                                .withComment(student.getComments())
                                .build();
                try {
                    StudentAttributes updatedStudent = logic.updateStudentCascade(updateOptions);
                    enrolledStudents.add(updatedStudent);
                } catch (InvalidParametersException | EntityDoesNotExistException
                        | EntityAlreadyExistsException exception) {
                    // Unsuccessfully enrolled students will not be returned.
                    return;
                }
            } else {
                // The student is new.
                try {
                    StudentAttributes newStudent = logic.createStudent(student);
                    enrolledStudents.add(newStudent);
                } catch (InvalidParametersException | EntityAlreadyExistsException exception) {
                    // Unsuccessfully enrolled students will not be returned.
                    return;
                }
            }
        });
        return new JsonResult(new StudentsData(enrolledStudents));
    }

    private void validateTeamName(List<StudentAttributes> existingStudents, List<StudentAttributes> studentsToEnroll) {
        Map<String, String> teamInSection = new HashMap<>();
        for (StudentAttributes existingStudent : existingStudents) {
            teamInSection.put(existingStudent.getTeam(), existingStudent.getSection());
        }
        for (StudentAttributes studentToEnroll : studentsToEnroll) {
            if (teamInSection.containsKey(studentToEnroll.getTeam())
                    && !teamInSection.get(studentToEnroll.getTeam()).equals(studentToEnroll.getSection())) {
                throw new InvalidHttpRequestBodyException(String.format(ERROR_MESSAGE_SAME_TEAM_IN_DIFFERENT_SECTION,
                        studentToEnroll.getTeam(), teamInSection.get(studentToEnroll.getTeam()),
                        studentToEnroll.getSection()));
            }
            teamInSection.put(studentToEnroll.getTeam(), studentToEnroll.getSection());
        }
    }
}
